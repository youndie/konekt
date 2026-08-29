package io.konekt.mocks.traffic

import io.konekt.events.EventTopics
import io.konekt.feature.roaming.server.domain.RoamingConsumption
import io.konekt.feature.roaming.server.domain.RoamingPackages
import io.konekt.feature.roaming.server.domain.Zones
import io.konekt.feature.usage.server.data.UsageCounterCards
import io.konekt.feature.usage.server.domain.ConsumeUsageUseCase
import io.konekt.feature.usage.server.domain.UsageCounter
import io.konekt.realtime.ComponentBroadcaster
import io.konekt.roaming.RoamingPackageCards
import io.konekt.time.KonektClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import ru.workinprogress.booblik.Offset
import ru.workinprogress.booblik.PartitionId
import ru.workinprogress.booblik.TopicName
import ru.workinprogress.booblik.net.client.BooblikConnection
import ru.workinprogress.booblik.net.client.Consumer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

// The other end of the chain: read `usage`, decrement the counter, push the new card.
//
// THE POSITION LIVES HERE. booblik stores no consumer offsets — that absence is what removes the
// group coordinator and the cluster consensus behind it — so a restarting consumer must be told where
// to resume. This one starts from wherever the broker is now rather than from zero, which is right
// for simulated traffic and wrong for anything real: replaying a day of usage on a restart would
// empty every counter in the product.
class UsageConsumer(
    private val connection: BooblikConnection,
    private val consume: ConsumeUsageUseCase,
    private val push: ComponentBroadcaster,
    // The card builder rather than its output, because the caption it writes now depends on the time
    // and on a price list. Injected for the same reason the clock is: a screen whose copy is decided
    // by a global is a screen no test can put in the low state on purpose.
    private val cards: UsageCounterCards,
    // The roaming half of the same chain. An event carrying a zone spends a roaming package instead
    // of the home counter — see `apply`.
    private val roaming: RoamingPackages,
    private val roamingCards: RoamingPackageCards,
    private val clock: KonektClock,
    private val json: Json = Json,
    private val pollInterval: Duration = 200.milliseconds,
) {
    private val logger = LoggerFactory.getLogger("io.konekt.mocks.traffic.consumer")

    fun start(
        scope: CoroutineScope,
        partition: PartitionId,
        from: Offset,
    ): Job =
        scope.launch {
            val consumer = Consumer(connection, TopicName(EventTopics.USAGE), partition, from)
            while (isActive) {
                try {
                    drain(consumer)
                } catch (cancellation: kotlinx.coroutines.CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    logger.warn("a usage poll failed", failure)
                }
                delay(pollInterval)
            }
        }

    suspend fun drain(consumer: Consumer): Int {
        val records = consumer.poll().records
        records.forEach { apply(String(it)) }
        return records.size
    }

    suspend fun apply(payload: String) {
        val event = json.parseToJsonElement(payload) as? JsonObject ?: return
        val subscriberId = event["subscriberId"]?.jsonPrimitive?.content ?: return

        // WHERE THE DATA WAS USED, and absent means home. Defaulted rather than required because
        // every event written before roaming existed omits it, and they all meant home — the same
        // reason the payload's zone is defaulted.
        val zone = event["zone"]?.jsonPrimitive?.content ?: Zones.HOME
        if (zone != Zones.HOME) {
            applyRoaming(subscriberId, zone, event)
            return
        }

        val kind =
            UsageCounter.Kind.entries.firstOrNull { it.wireName == event["kind"]?.jsonPrimitive?.content } ?: return
        val units = event["units"]?.jsonPrimitive?.content?.toLongOrNull() ?: return

        val updated =
            consume(ConsumeUsageUseCase.Params(subscriberId, kind, units)).getOrNull()
                // No counter for this subscriber and kind. Not an error: a subscriber who has bought
                // nothing has nothing to spend, and the simulator does not know that.
                ?: return

        // Pushed by the component id the screen already has, so the client replaces a node rather
        // than reloading a screen. That is the whole difference a live update makes.
        push.push(subscriberId, UsageCounterCards.idOf(updated), cards.of(updated, clock.now()))
    }

    // FIRST USE ABROAD, which is where a dormant package stops being dormant. The activation is not a
    // separate call this method makes first — `consume` does both, because "starts on first
    // connection" means they are one event and an API that separates them permits one without the
    // other.
    private suspend fun applyRoaming(
        subscriberId: String,
        zone: String,
        event: JsonObject,
    ) {
        val megabytes = event["units"]?.jsonPrimitive?.content?.toLongOrNull() ?: return

        // ONE READING FOR BOTH the consumption and the card that announces it: an update captioned
        // against a later instant than the one it was counted at would say a package expired between
        // the two lines of this method.
        val at = clock.now()
        val result = roaming.consume(subscriberId, zone, megabytes, at)
        // Nothing bought for this zone. Not an error, and not silent either: it is what being abroad
        // without a package looks like, and the simulator has no way to know.
        if (result !is RoamingConsumption.Counted) return

        if (result.started) {
            logger.info(
                "roaming package {} started in {} and now ends {}",
                result.pkg.id,
                zone,
                result.pkg.expiresAt,
            )
        }

        push.push(subscriberId, RoamingPackageCards.idOf(result.pkg), roamingCards.of(result.pkg, at))
    }
}
