package io.konekt.mocks.traffic

import io.github.youndie.booblik.Offset
import io.github.youndie.booblik.PartitionId
import io.github.youndie.booblik.TopicName
import io.github.youndie.booblik.net.client.Consumer
import io.github.youndie.booblik.net.client.FetchFailedException
import io.github.youndie.booblik.net.wire.ErrorCode
import io.konekt.db.ConsumerPositions
import io.konekt.events.BrokerConnection
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

// The other end of the chain: read `usage`, decrement the counter, push the new card.
//
// THE POSITION LIVES IN THE DATABASE, BESIDE THE DECREMENTS (`B-134`). booblik stores no consumer
// offsets — that absence is what removes the group coordinator — so this consumer keeps its own, in
// `consumer_position`, and moves it in the SAME transaction as the decrements of the batch it covers:
// first, and only from the offset the batch was read at (booblik's `feature-consumer-position`). A
// restart carries on where the last committed batch ended, a crash mid-batch leaves both untouched,
// and two consumers of one partition apply each event once.
//
// Until `B-134` the position lived here, in memory, and every process started at the end of the log:
// usage published while a pod was down was never applied at all.
class UsageConsumer(
    // THE HOLDER, NOT THE SOCKET (`B-107`). A `BooblikConnection` taken once is a socket held for
    // the life of the process, and a broker pod being replaced then wedges this loop for ever.
    private val broker: BrokerConnection,
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
    // Where this consumer has got to (`B-134`). Required, not defaulted: a consumer with nowhere to
    // keep its position is the one that loses a restart's worth of usage.
    private val positions: ConsumerPositions,
    private val pollInterval: Duration = 200.milliseconds,
) {
    private val logger = LoggerFactory.getLogger("io.konekt.mocks.traffic.consumer")

    // Which socket this loop is holding, so a reconnect asks to replace THAT one rather than
    // whatever is live by the time it gets there — see `BrokerConnection.generation`.
    private var brokerGeneration: Int = broker.generation

    fun start(
        scope: CoroutineScope,
        partition: PartitionId,
        from: Offset,
    ): Job =
        scope.launch {
            val topic = TopicName(EventTopics.USAGE)
            brokerGeneration = broker.generation
            // [from] is the first position only when none is stored; a stored one wins, so a caller
            // that passes a stale number cannot move a consumer backwards or forwards.
            val start = Offset(positions.seed(keyOf(partition), from.value))
            var consumer = Consumer(broker.connection, topic, partition, start)
            while (isActive) {
                try {
                    drain(consumer, partition)
                } catch (cancellation: kotlinx.coroutines.CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    // THE RECOVERY NEEDS A RECOVERY, and the live contour is what said so. A broker
                    // pod is replaced, this loop finds the dead socket on its very next poll — 200 ms
                    // later, while the new pod is still starting — and `BrokerConnection.reconnect`
                    // dials in a constructor and THROWS. Thrown from inside a catch block, that
                    // leaves the try/catch, leaves the while, and kills the coroutine: the storm of
                    // EOFs stops, which reads like a fix, and the consumer is simply gone.
                    //
                    // Observed exactly once in production, at 07:23:59, and it is why "the next
                    // caller asks again" only works if there IS a next caller.
                    consumer =
                        try {
                            recoverFrom(failure, consumer, topic, partition) { brokerGeneration = it }
                        } catch (cancellation: kotlinx.coroutines.CancellationException) {
                            throw cancellation
                        } catch (stillDown: Exception) {
                            logger.warn("the broker is not back yet; retrying in {}", pollInterval, stillDown)
                            consumer
                        }
                }
                delay(pollInterval)
            }
        }

    // THE TWO WAYS THIS LOOP CANNOT WIN ON ITS OWN, and one way it can carry on unchanged.
    //
    // Returns the consumer to poll next — the same one when there was nothing to recover from, so a
    // transient failure still costs one poll interval and nothing else.
    private suspend fun recoverFrom(
        failure: Exception,
        consumer: Consumer,
        topic: TopicName,
        partition: PartitionId,
        reconnected: (Int) -> Unit,
    ): Consumer =
        when {
            BrokerConnection.isFinished(failure) -> {
                // THE SOCKET, NOT THE OFFSET. The position is fine and the connection is not, so
                // resume exactly where this consumer had got to — the STORED position, which is the
                // last batch that committed, not the reader's, which may be past a batch that did not.
                val resumeAt = stored(partition) ?: consumer.position
                logger.warn("the broker connection broke at offset {}", resumeAt.value, failure)
                reconnected(broker.reconnect(brokerGeneration))
                Consumer(broker.connection, topic, partition, resumeAt)
            }

            failure is FetchFailedException && failure.code == ErrorCode.OFFSET_OUT_OF_RANGE -> {
                // RETENTION PASSED THIS CONSUMER (`B-100` made it reachable): the offset it asks for
                // was deleted. It carries on from the start of what is left — not from the end, which
                // would throw away everything retention kept — and the loud part is not the seek, it
                // is saying how many records were SKIPPED. The jump is stored like any other move,
                // from the position it replaces, so a consumer that lost the race reads the winner's.
                val lost = stored(partition) ?: consumer.position
                val resumeAt = startOf(partition)
                if (!positions.advance(keyOf(partition), lost.value, resumeAt.value)) {
                    return Consumer(broker.connection, topic, partition, stored(partition) ?: resumeAt)
                }
                logger.warn(
                    "the broker no longer has offset {} on partition {} — retention passed this " +
                        "consumer, so {} usage events were never applied. Resuming at {}",
                    lost.value,
                    partition.value,
                    resumeAt.value - lost.value,
                    resumeAt.value,
                )
                Consumer(broker.connection, topic, partition, resumeAt)
            }

            else -> {
                logger.warn("a usage poll failed", failure)
                consumer
            }
        }

    // WHERE THE LIVE LOG STARTS, asked of METADATA rather than read out of a poll: a fetch below
    // the start is exactly the refusal that brings a caller here.
    private suspend fun startOf(partition: PartitionId): Offset {
        val answer = broker.connection.metadata(listOf(TopicName(EventTopics.USAGE)))
        return answer.topics
            .singleOrNull()
            ?.partitions
            ?.firstOrNull { it.partition == partition }
            ?.logStartOffset
            ?: Offset.ZERO
    }

    private suspend fun stored(partition: PartitionId): Offset? = positions.load(keyOf(partition))?.let(::Offset)

    // ONE BATCH, ONE TRANSACTION: the position moves from where this batch was read to just past it,
    // and every decrement of the batch commits with it or not at all. The pushes wait for the commit
    // — a card announcing a decrement that rolled back would be a screen that lies.
    //
    // When the stored position is not where this batch was read, another consumer applied it (or
    // this one is behind its own last commit): nothing is applied, and the reader is moved to the
    // stored position. Returns how many records were applied.
    suspend fun drain(
        consumer: Consumer,
        partition: PartitionId,
    ): Int {
        val base = consumer.position
        val records = consumer.poll().records
        if (records.isEmpty()) return 0
        val next = Offset(base.value + records.size)

        val pushes = mutableListOf<suspend () -> Unit>()
        val applied =
            positions.advance(keyOf(partition), base.value, next.value) {
                records.forEach { record -> effectOf(String(record))?.let(pushes::add) }
            }
        if (!applied) {
            val at = stored(partition) ?: base
            logger.info(
                "usage records {}..{} were already applied; carrying on from the stored position {}",
                base.value,
                next.value - 1,
                at.value,
            )
            consumer.seek(at)
            return 0
        }
        pushes.forEach { it() }
        return records.size
    }

    // One event outside any position: its effect, then its push. What [drain] does per record, minus
    // the transaction — kept for the tests that are about what an event DOES, not where it was read.
    suspend fun apply(payload: String) {
        effectOf(payload)?.invoke()
    }

    // The event's effect, performed now — inside [drain]'s transaction when called from there — and
    // the push that announces it, returned rather than sent, so it can wait for the commit.
    private suspend fun effectOf(payload: String): (suspend () -> Unit)? {
        val event = json.parseToJsonElement(payload) as? JsonObject ?: return null
        val subscriberId = event["subscriberId"]?.jsonPrimitive?.content ?: return null

        // WHERE THE DATA WAS USED, and absent means home. Defaulted rather than required because
        // every event written before roaming existed omits it, and they all meant home — the same
        // reason the payload's zone is defaulted.
        val zone = event["zone"]?.jsonPrimitive?.content ?: Zones.HOME
        if (zone != Zones.HOME) return roamingEffectOf(subscriberId, zone, event)

        val kind =
            UsageCounter.Kind.entries.firstOrNull { it.wireName == event["kind"]?.jsonPrimitive?.content }
                ?: return null
        val units = event["units"]?.jsonPrimitive?.content?.toLongOrNull() ?: return null

        val updated =
            consume(ConsumeUsageUseCase.Params(subscriberId, kind, units)).getOrNull()
                // No counter for this subscriber and kind. Not an error: a subscriber who has bought
                // nothing has nothing to spend, and the simulator does not know that.
                ?: return null

        // Pushed by the component id the screen already has, so the client replaces a node rather
        // than reloading a screen. That is the whole difference a live update makes.
        return { push.push(subscriberId, UsageCounterCards.idOf(updated), cards.of(updated, clock.now())) }
    }

    // FIRST USE ABROAD, which is where a dormant package stops being dormant. The activation is not a
    // separate call this method makes first — `consume` does both, because "starts on first
    // connection" means they are one event and an API that separates them permits one without the
    // other.
    private suspend fun roamingEffectOf(
        subscriberId: String,
        zone: String,
        event: JsonObject,
    ): (suspend () -> Unit)? {
        val megabytes = event["units"]?.jsonPrimitive?.content?.toLongOrNull() ?: return null

        // ONE READING FOR BOTH the consumption and the card that announces it: an update captioned
        // against a later instant than the one it was counted at would say a package expired between
        // the two lines of this method.
        val at = clock.now()
        val result = roaming.consume(subscriberId, zone, megabytes, at)
        // Nothing bought for this zone. Not an error, and not silent either: it is what being abroad
        // without a package looks like, and the simulator has no way to know.
        if (result !is RoamingConsumption.Counted) return null

        if (result.started) {
            logger.info(
                "roaming package {} started in {} and now ends {}",
                result.pkg.id,
                zone,
                result.pkg.expiresAt,
            )
        }

        return { push.push(subscriberId, RoamingPackageCards.idOf(result.pkg), roamingCards.of(result.pkg, at)) }
    }

    companion object {
        // The name this consumer's position is stored under. Renaming it starts a new consumer — at
        // the end of the log, with everything the old name had not applied left unapplied.
        const val NAME = "konekt-usage"

        fun keyOf(partition: PartitionId): ConsumerPositions.Key =
            ConsumerPositions.Key(NAME, EventTopics.USAGE, partition.value)
    }
}
