package io.konekt.mocks

import io.github.youndie.kompot.generated.generatedKonektSerializersModule
import io.github.youndie.kompot.kompotCoreSerializersModule
import io.github.youndie.kompot.realtime.UpdateComponentMessage
import io.github.youndie.kompot.realtime.server.KompotUpdateBroadcaster
import io.konekt.components.CounterStates
import io.konekt.components.UsageCounterCardComponent
import io.konekt.db.tables.SubscriberTable
import io.konekt.events.BrokerHarness
import io.konekt.events.EventTopics
import io.konekt.feature.realtime.shared.api.RealtimeStream
import io.konekt.feature.roaming.server.domain.InMemoryRoamingPackages
import io.konekt.feature.usage.server.data.ExposedUsageCounters
import io.konekt.feature.usage.server.data.StaticUsageAddOns
import io.konekt.feature.usage.server.data.UsageCounterCards
import io.konekt.feature.usage.server.domain.ConsumeUsageUseCase
import io.konekt.feature.usage.server.domain.UsageCounter
import io.konekt.mocks.traffic.TrafficSimulator
import io.konekt.mocks.traffic.UsageConsumer
import io.konekt.realtime.ComponentBroadcaster
import io.konekt.roaming.RoamingPackageCards
import io.konekt.testing.PostgresHarness
import io.konekt.time.KonektClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.plus
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import ru.workinprogress.booblik.TopicName
import ru.workinprogress.booblik.net.client.Consumer
import ru.workinprogress.booblik.net.client.Producer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

// The whole chain, end to end and for real: simulator → broker → consumer → counter → live update.
//
// Every link is the real one. A test that wrote counters directly would prove the arithmetic and
// nothing about the path, and the path is what B-16 exists for — the simulator publishes rather than
// writing precisely so that this is the thing being exercised.
@OptIn(ExperimentalUuidApi::class)
class TrafficChainTest {
    private val clock = KonektClock { Instant.fromEpochMilliseconds(1_700_000_000_000) }
    private val json =
        Json {
            ignoreUnknownKeys = true
            classDiscriminator = "type"
            serializersModule = kompotCoreSerializersModule + generatedKonektSerializersModule
        }

    private val counters = ExposedUsageCounters(PostgresHarness.database, clock)

    // One scope and one broadcaster per test — JUnit builds a new instance for each — started here
    // rather than inside a test. kompot refuses to broadcast through a broadcaster that was never
    // started, and says so: a publish reaching a bus nobody is collecting from would otherwise be
    // silence, which is the failure it is hardest to attribute.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val broadcaster = KompotUpdateBroadcaster().also { it.start(scope) }
    private val push = ComponentBroadcaster(broadcaster, json)

    // The card builder the consumer pushes through. Its caption is a projection now, so it needs
    // the same clock the counters were granted on — a card built on the real clock would read
    // "runs out in about 19 000 days" against a counter stamped in 2023.
    private val cards = UsageCounterCards(StaticUsageAddOns(), clock)
    private val roaming = InMemoryRoamingPackages { clock.now() }
    private val roamingCards = RoamingPackageCards(clock)

    @AfterTest
    fun stop() {
        scope.cancel()
    }

    private lateinit var subscriberId: String

    @BeforeTest
    fun seed() {
        PostgresHarness.truncateAll()
        val id = Uuid.random().toString()
        subscriberId = id
        transaction(PostgresHarness.database) {
            SubscriberTable.insert {
                it[SubscriberTable.id] = id
                it[msisdn] = "1555010${(1000..9999).random()}"
                it[createdAt] = 0
            }
        }
    }

    @Test
    fun `traffic published to the broker moves the counter and pushes the new card`() =
        runBlocking {
            val connection = BrokerHarness.connect(scope)
            try {
                val listener = Channel<String>(Channel.UNLIMITED)
                broadcaster.subscribe(RealtimeStream.topicOf(subscriberId), listener)

                counters.grant(subscriberId, UsageCounter.Kind.DATA, 10_000)

                val producer = Producer(connection, scope)
                val handle = producer.topic(TopicName(EventTopics.USAGE))
                // Where the topic already stands. The broker is shared by every test in this JVM, so
                // a consumer starting at zero would replay somebody else's traffic.
                val start =
                    Consumer(connection, TopicName(EventTopics.USAGE), handle.partitions.first()).let {
                        it.poll()
                        it.position
                    }

                val simulator =
                    TrafficSimulator(
                        producer,
                        subscribers = { listOf(subscriberId) },
                        travelling = { roaming.travelling() },
                        // NO ARRIVALS IN THIS TEST, and the empty list is the honest way to say so:
                        // the subject here is a home counter travelling the whole chain, and a
                        // simulated arrival would put a second event on the topic that the assertions
                        // below would have to subtract. The arrival case at the bottom of this file
                        // covers it.
                        awaitingArrival = { emptyList() },
                        clock = clock,
                        megabytesPerTick = 25,
                    )
                simulator.tick(handle)

                val consumer = Consumer(connection, TopicName(EventTopics.USAGE), handle.partitions.first(), start)
                val applied =
                    UsageConsumer(
                        connection,
                        ConsumeUsageUseCase(counters),
                        push,
                        cards,
                        roaming,
                        roamingCards,
                        clock,
                        json,
                    ).drain(consumer)

                // THREE, one per counter kind, and the number is asserted rather than left loose:
                // the simulator publishes data, minutes and messages every tick, and a kind that
                // silently stopped being published is a counter state nobody could reach again.
                // That is exactly what this file's subject used to be — only DATA was ever sent.
                assertEquals(3, applied, "the simulator's events did not all reach the consumer")
                assertEquals(9_975, assertNotNull(counters.find(subscriberId, UsageCounter.Kind.DATA)).remainingUnits)

                // And the screen hears about it. This is the link the whole chain exists for: a
                // counter that moved and nobody was told is a counter the subscriber refreshes to see.
                val frame = assertNotNull(listener.tryReceive().getOrNull(), "the counter moved and nothing was pushed")
                val message = json.decodeFromString(UpdateComponentMessage.serializer(), frame)
                assertEquals("counter-data", message.componentId)
                // "9.7 GB left" and not "9975 MB left": a subscriber does not read megabytes past a
                // thousand, and the formatting is the server's because it is the only side that can.
                assertEquals("9.7 GB left", (message.component as UsageCounterCardComponent).valueText)
            } finally {
                connection.close()
            }
        }

    @Test
    fun `stopping the traffic stops the movement and nothing else changes`() =
        runBlocking {
            counters.grant(subscriberId, UsageCounter.Kind.DATA, 1_000)
            val consumer =
                UsageConsumer(
                    BrokerHarness.connect(),
                    ConsumeUsageUseCase(counters),
                    push,
                    cards,
                    roaming,
                    roamingCards,
                    clock,
                    json,
                )

            consumer.apply(event(units = 100))
            val afterOne = assertNotNull(counters.find(subscriberId, UsageCounter.Kind.DATA)).remainingUnits

            // Nothing published, nothing applied. The assertion is that the counter is EXACTLY where
            // it was, not merely that it did not go up: a poller that re-applied its last event would
            // pass a looser check.
            assertEquals(900, afterOne)
            assertEquals(afterOne, assertNotNull(counters.find(subscriberId, UsageCounter.Kind.DATA)).remainingUnits)
        }

    @Test
    fun `a counter is floored at zero rather than going negative`() =
        runBlocking {
            counters.grant(subscriberId, UsageCounter.Kind.DATA, 10)
            val consumer =
                UsageConsumer(
                    BrokerHarness.connect(),
                    ConsumeUsageUseCase(counters),
                    push,
                    cards,
                    roaming,
                    roamingCards,
                    clock,
                    json,
                )

            consumer.apply(event(units = 400))

            val counter = assertNotNull(counters.find(subscriberId, UsageCounter.Kind.DATA))
            // A screen that says minus three hundred and ninety megabytes is worse than one that says
            // zero, and the clamp is in SQL because two decrements arriving together both pass a
            // read-then-check.
            assertEquals(0, counter.remainingUnits)
            assertTrue(counter.isExhausted)
        }

    @Test
    fun `the copy changes with the state and not only the colour`(): Unit =
        runBlocking {
            counters.grant(subscriberId, UsageCounter.Kind.DATA, 1_000)
            val consumer =
                UsageConsumer(
                    BrokerHarness.connect(),
                    ConsumeUsageUseCase(counters),
                    push,
                    cards,
                    roaming,
                    roamingCards,
                    clock,
                    json,
                )

            consumer.apply(event(units = 950))
            val low = assertNotNull(counters.find(subscriberId, UsageCounter.Kind.DATA))
            assertTrue(low.isLow, "left ${low.remainingUnits} of ${low.limitUnits} and it is not being called low")

            val card = cards.of(low)
            assertEquals(CounterStates.LOW, card.state)
            // The canvas's rule: a subscriber who is nearly out is told what that means, not shown a
            // different colour and left to work it out.
            assertNotNull(card.captionText)
        }

    @Test
    fun `usage for a subscriber who bought nothing is ignored rather than failing`() =
        runBlocking {
            val consumer =
                UsageConsumer(
                    BrokerHarness.connect(),
                    ConsumeUsageUseCase(counters),
                    push,
                    cards,
                    roaming,
                    roamingCards,
                    clock,
                    json,
                )

            // No counter exists. The simulator does not know who has bought what, and a consumer that
            // threw here would stop the whole poll for everybody else.
            consumer.apply(event(units = 25))

            assertEquals(null, counters.find(subscriberId, UsageCounter.Kind.DATA))
        }

    // ARRIVAL, WHICH IS NOW THE SIMULATION'S AND NOT A ROUTE'S.
    //
    // `B-88` deleted `/api/v1/dev/roaming/arrive` — public, taking `subscriberId` from the query, and
    // the only way to start a roaming package, so the demonstration of the whole feature ran through
    // a route documented as never shippable. What replaces it is a DELAY: a package lies dormant long
    // enough to be looked at, and then the simulation flies its owner out.
    //
    // The delay is the whole design, so this asserts BOTH sides of it. A test that only checked the
    // arrival would pass on a simulator that started every package on the first tick, which is
    // precisely the behaviour the deleted route existed to avoid.
    //
    // Driven through the real broker like every other case here: what is being checked is what the
    // simulator PUBLISHES, and a test that asked the repository instead would be checking the query
    // this feature already had.
    @Test
    fun `a package dormant long enough departs, and a fresh one stays dormant`() =
        runBlocking {
            val connection = BrokerHarness.connect(scope)
            try {
                val now = clock.now()
                roaming.grant("order-old", subscriberId, "tr", 10_240, 30, now - 120.seconds)
                // Ten seconds old against a ninety-second delay: still dormant, and must stay so.
                roaming.grant("order-fresh", "$subscriberId-fresh", "eu", 5_120, 14, now - 10.seconds)

                val producer = Producer(connection, scope)
                val handle = producer.topic(TopicName(EventTopics.USAGE))
                val start =
                    Consumer(connection, TopicName(EventTopics.USAGE), handle.partitions.first()).let {
                        it.poll()
                        it.position
                    }

                TrafficSimulator(
                    producer,
                    // Nobody with a home counter and nobody travelling, so every event this tick
                    // produces is an arrival and the assertion below needs no subtraction.
                    subscribers = { emptyList() },
                    travelling = { roaming.travelling() },
                    awaitingArrival = { before -> roaming.awaitingArrival(before) },
                    clock = clock,
                    dormantFor = 90.seconds,
                ).tick(handle)

                val published =
                    Consumer(connection, TopicName(EventTopics.USAGE), handle.partitions.first(), start)
                        .poll()
                        .records
                        .map { String(it) }

                assertEquals(1, published.size, "the tick published something other than one arrival: $published")
                val arrival = published.single()
                assertTrue(subscriberId in arrival, "the arrival is not for the package that landed: $arrival")
                assertTrue("\"zone\":\"tr\"" in arrival, "the arrival names a zone the package is not for: $arrival")
                // ONE MEGABYTE. An arrival crosses the line from dormant to started; an amount large
                // enough to move the number on the card would make the first thing a subscriber sees
                // after landing a figure that had already dropped.
                assertTrue("\"units\":1" in arrival, "an arrival spent more than the crossing costs: $arrival")
            } finally {
                connection.close()
            }
        }

    private fun event(units: Long) = """{"subscriberId":"$subscriberId","kind":"data","units":$units}"""
}
