package io.konekt.mocks.traffic

import io.konekt.events.EventTopics
import io.konekt.feature.roaming.server.domain.Travelling
import io.konekt.feature.roaming.server.domain.Zones
import io.konekt.time.KonektClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import ru.workinprogress.booblik.TopicName
import ru.workinprogress.booblik.net.client.Producer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

// Nothing in this build produces real traffic, and a counter that never moves cannot demonstrate a
// live screen.
//
// IT PUBLISHES TO THE BROKER rather than writing counters directly, and that is the whole point of
// its existing: the path exercised is the one a real integration would use — broker, consumer,
// counter, realtime, screen. A simulator that wrote the database would prove none of it, and would
// be the second place counters are decremented.
//
// The rate is configuration and not a die: a demonstration that moves a counter one time in ten is a
// demonstration that fails while somebody is watching.
class TrafficSimulator(
    private val producer: Producer,
    private val subscribers: suspend () -> List<String>,
    // Trips already under way. Deliberately NOT "everyone holding a roaming package": a dormant
    // package must stay dormant until somebody starts it, or the state this feature exists to show —
    // bought, not counting — is over five seconds after the purchase and nobody ever sees it.
    private val travelling: suspend () -> List<Travelling>,
    // WHO HAS LANDED SINCE THE LAST TICK: dormant packages older than `dormantFor`.
    //
    // This is `B-88`'s replacement for `/api/v1/dev/roaming/arrive` — a public POST that took the
    // subscriber from the query rather than from a token, so wherever it was enabled anybody could
    // start a stranger's package and spend their allowance. The demonstration of the whole feature
    // ran through the one route documented as never shippable.
    private val awaitingArrival: suspend (Instant) -> List<Travelling>,
    private val clock: KonektClock,
    private val json: Json = Json,
    private val interval: Duration = 5.seconds,
    private val megabytesPerTick: Long = 25,
    // THE OTHER TWO KINDS, and without them two of the three counter states the canvas draws could
    // never appear. A counter that is granted and never spent sits at full for ever, so *Running
    // low* and *Used up* were states the component could draw and the product could not produce.
    //
    // The rates are chosen so a person watching sees both happen: fifty messages at one a tick is
    // exhausted in about four minutes, three hundred minutes at one a tick is low in twenty. Data is
    // the slow one, which is also true of a real line.
    private val minutesPerTick: Long = 1,
    private val messagesPerTick: Long = 1,
    // HOW LONG A PACKAGE LIES DORMANT BEFORE THE SIMULATION FLIES ITS OWNER OUT.
    //
    // The number is the whole design. The route this replaces existed because a package that started
    // itself five seconds after purchase makes the state this feature is about — bought, and not
    // counting — unobservable; a delay long enough to look at and short enough to wait through gives
    // both. Ninety seconds is a demonstration a person can narrate: buy it, show the dormant card,
    // talk for a minute, watch it start.
    //
    // Configuration rather than a constant, for the same reason the rates are: the end-to-end stand
    // sets it to a few seconds so a scenario does not sleep for a minute and a half.
    private val dormantFor: Duration = 90.seconds,
) {
    private val logger = LoggerFactory.getLogger("io.konekt.mocks.traffic")

    fun start(scope: CoroutineScope): Job =
        scope.launch {
            val topic = producer.topic(TopicName(EventTopics.USAGE))
            while (isActive) {
                try {
                    tick(topic)
                } catch (cancellation: kotlinx.coroutines.CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    // A tick that failed is a tick, not the end of the simulator. The broker being
                    // briefly away is the ordinary case here.
                    logger.warn("a traffic tick failed", failure)
                }
                delay(interval)
            }
        }

    suspend fun tick(topic: ru.workinprogress.booblik.net.client.TopicHandle): Int {
        val ids = subscribers()
        ids.forEach { subscriberId ->
            // ONE EVENT PER KIND, and all three keyed by the subscriber — so every event about one
            // subscriber lands in one partition and their decrements stay in the order they
            // happened. Out of order they still sum to the same number, but a screen that jumps
            // backwards for a moment reads as a bug.
            //
            // A subscriber with no counter of that kind is not an error and needs no check here: the
            // consumer finds nothing to decrement and returns. A roaming package holder gets no
            // minutes event because they have no minutes counter, not because this asked.
            usageAmounts.forEach { (kind, units) ->
                topic.send(
                    usageEvent(subscriberId, Zones.HOME, kind, units).toByteArray(),
                    subscriberId.toByteArray(),
                )
            }
        }
        // The same event with a zone on it. One code path publishes both, so a roaming decrement
        // travels the identical broker → consumer → counter → realtime → screen route as a home one —
        // which is the reason the simulator publishes at all rather than writing the database.
        val trips = travelling()
        trips.forEach { trip ->
            // DATA ONLY, and that is the catalogue's own answer rather than a simplification here: a
            // roaming package includes no minutes and no messages, which its detail frame says out
            // loud. Sending them would spend a home allowance while the subscriber is abroad.
            topic.send(
                usageEvent(trip.subscriberId, trip.zone, UsageKinds.DATA, megabytesPerTick).toByteArray(),
                trip.subscriberId.toByteArray(),
            )
        }

        // ARRIVALS, after the trips already under way and before the flush. One megabyte per package,
        // because this is an ARRIVAL and not a session: what it is for is crossing the line from
        // dormant to started, and the amount it spends doing so should be small enough not to muddy
        // the number on the card.
        //
        // PUBLISHED LIKE EVERYTHING ELSE HERE, not written to the table. The value of it is that it
        // enters the same pipe real traffic would — broker, consumer, package, realtime, screen. A
        // version that called `RoamingPackages.consume` directly would light the card up and prove
        // nothing about the path.
        val arrivals = awaitingArrival(clock.now() - dormantFor)
        arrivals.forEach { arrival ->
            topic.send(
                usageEvent(arrival.subscriberId, arrival.zone, UsageKinds.DATA, ARRIVAL_MB).toByteArray(),
                arrival.subscriberId.toByteArray(),
            )
        }

        producer.flush()
        return ids.size * usageAmounts.size + trips.size + arrivals.size
    }

    // The wire names of the counter kinds, spelled where the events are built. They are the usage
    // feature's `UsageCounter.Kind.wireName`, and this module cannot see that enum — the consumer on
    // the other side matches against it, so a name that drifted would decrement nothing and report
    // nothing, which is the quietest failure this simulator has.
    private companion object {
        // An arrival is one megabyte. Named rather than written as `1` beside the event, because the
        // number is a decision — see `dormantFor` above — and not an increment.
        const val ARRIVAL_MB = 1L
    }

    private object UsageKinds {
        const val DATA = "data"
        const val MINUTES = "minutes"
        const val MESSAGES = "messages"
    }

    private val usageAmounts: List<Pair<String, Long>>
        get() =
            listOf(
                UsageKinds.DATA to megabytesPerTick,
                UsageKinds.MINUTES to minutesPerTick,
                UsageKinds.MESSAGES to messagesPerTick,
            ).filter { it.second > 0 }

    private fun usageEvent(
        subscriberId: String,
        zone: String,
        kind: String,
        units: Long,
    ): String =
        json.encodeToString(
            JsonObject.serializer(),
            JsonObject(
                buildMap {
                    put("subscriberId", JsonPrimitive(subscriberId))
                    put("kind", JsonPrimitive(kind))
                    put("units", JsonPrimitive(units))
                    // Omitted for home rather than written as "home", so the events this simulator
                    // produces stay byte-identical to the ones it produced before roaming existed —
                    // and the consumer's default is exercised by the ordinary path rather than only
                    // by a test.
                    if (zone != Zones.HOME) put("zone", JsonPrimitive(zone))
                },
            ),
        )
}
