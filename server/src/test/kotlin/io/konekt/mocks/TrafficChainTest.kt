package io.konekt.mocks

import io.github.youndie.kompot.generated.generatedKonektSerializersModule
import io.github.youndie.kompot.kompotCoreSerializersModule
import io.github.youndie.kompot.realtime.UpdateComponentMessage
import io.github.youndie.kompot.realtime.server.KompotUpdateBroadcaster
import io.konekt.components.CounterStates
import io.konekt.components.UsageCounterCardComponent
import io.konekt.db.tables.SubscriberTable
import io.konekt.events.BrokerConnection
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
import io.konekt.mocks.traffic.UsageChain
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
import kotlinx.coroutines.withTimeoutOrNull
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

    // The card builder the consumer pushes through. Its caption is a projection, so it is drawn
    // against the same instant the counters were granted on — a card built on the real clock would
    // read "runs out in about 19 000 days" against a counter stamped in 2023. The instant is an
    // argument rather than a clock the factory holds (`B-96`).
    private val cards = UsageCounterCards(StaticUsageAddOns())
    private val roaming = InMemoryRoamingPackages { clock.now() }
    private val roamingCards = RoamingPackageCards()

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

    // A BROKER THAT IS NOT BACK YET MUST NOT KILL THE LOOP (`B-107`, second half).
    //
    // The first version of the recovery reconnected from inside the catch block, and
    // `BrokerConnection.reconnect` dials in a constructor — so a broker that had gone away and not
    // yet come back threw out of the catch, out of the `while`, and took the coroutine with it.
    //
    // On the contour that read like a fix: the five-a-second storm of `EOFException` stopped after
    // exactly one line. It stopped because there was nothing left to poll. A consumer that dies
    // quietly and one that recovered are indistinguishable in a log.
    @Test
    fun `a consumer whose broker stays down keeps trying instead of dying`() =
        runBlocking {
            val isolated = BrokerHarness.isolated()
            val brokerConnection = isolated.broker()
            try {
                counters.grant(subscriberId, UsageCounter.Kind.DATA, 10_000)

                val partition =
                    isolated
                        .connect()
                        .metadata(listOf(TopicName(EventTopics.USAGE)))
                        .topics
                        .single()
                        .partitions
                        .first()
                        .partition

                val job =
                    UsageConsumer(
                        brokerConnection,
                        ConsumeUsageUseCase(counters),
                        push,
                        cards,
                        roaming,
                        roamingCards,
                        clock,
                        json,
                    ).start(scope, partition, ru.workinprogress.booblik.Offset.ZERO)

                // THE BROKER GOES AWAY AND STAYS AWAY. Not a replaced pod — a port that answers
                // nothing, which is the state a pod is in for the seconds between the old one dying
                // and the new one listening. Every reconnect attempt in that window fails.
                isolated.close()

                // Many poll intervals, so this is not one lucky tick: the loop must have failed to
                // poll, failed to reconnect, and gone round again, repeatedly.
                kotlinx.coroutines.delay(3.seconds)

                assertTrue(
                    job.isActive,
                    "the consumer died while its broker was down — a recovery that throws out of the " +
                        "catch block it lives in takes the loop with it",
                )

                job.cancel()
            } finally {
                brokerConnection.close()
            }
        }

    // THE CHAIN STARTS AT THE END OF THE LOG, WHICH IT SAID IT DID AND DID NOT (`B-108`).
    //
    // The comment in `UsageChain` has always been right — replaying a day of invented usage on every
    // restart would empty every counter in the product — and the code under it built a consumer at
    // offset ZERO, polled once, and took the position: one `maxBytes` of records in from the START.
    // Measured on the stage deployment, it began at 11915 while the log ended at 374473, so a
    // restart replayed 362,558 historical events against live counters.
    //
    // Nothing caught it, and nothing would have: every other test in this file publishes AFTER the
    // consumer starts, which is the one arrangement in which the two offsets are the same number.
    // So this test publishes BEFORE.
    @Test
    fun `a chain starting on a log that already has records applies none of them`() =
        runBlocking {
            // ITS OWN BROKER, and that is not tidiness. This test has to put more than a megabyte on
            // the `usage` topic before it starts anything; on the shared container that is not
            // padding but pollution, and it broke `BrokerTopicsTest`, which reads its own probe back
            // and got a filler record. An empty log of its own also means the offsets below are
            // known rather than inherited.
            val isolated = BrokerHarness.isolated()
            val connection = isolated.connect()
            val brokerConnection = isolated.broker()
            try {
                counters.grant(subscriberId, UsageCounter.Kind.DATA, 10_000)

                val producer = Producer(connection, scope)
                val handle = producer.topic(TopicName(EventTopics.USAGE))

                // MORE THAN ONE `maxBytes` OF PADDING FIRST, and this is the whole reason the defect
                // survived two seasons of green tests.
                //
                // The broken version built a consumer at zero, polled ONCE and took the position —
                // which is `Consumer.DEFAULT_MAX_BYTES` (1 MiB) in from the start, not the end. On a
                // short log those two numbers are the SAME, because one poll reaches the end; every
                // other test in this file has a short log, so all of them passed over it. It took a
                // 141 MiB log in production to tell the difference.
                //
                // These records are inert: a subscriber id nothing has granted, so `apply` finds no
                // counter and returns. They exist to take up bytes.
                // THE SIZE IS NOT REASONED, IT IS ASSERTED. The first attempt used 24 records of
                // 48 KiB — 1.18 MiB, comfortably over `DEFAULT_MAX_BYTES` on paper — and one poll
                // still reached the end of the log. Rather than model what the broker does with
                // `maxBytes`, the precondition below states the property this test needs and fails
                // when it does not hold.
                val filler = "x".repeat(48 * 1024)
                repeat(120) {
                    handle.send(
                        """{"subscriberId":"padding-$it","kind":"data","units":1,"pad":"$filler"}""".toByteArray(),
                    )
                }
                producer.flush()

                // AND THEN THE HISTORY THAT WOULD HURT, on the far side of that first poll's reach.
                // These are this subscriber's own events, so a consumer that resumed anywhere before
                // them takes 3 000 units off a counter that owes nothing.
                repeat(30) { handle.send(event(units = 100).toByteArray(), subscriberId.toByteArray()) }
                producer.flush()

                // THE PRECONDITION, ASSERTED. Everything below is about the difference between "one
                // poll in from the start" and "the end", and on a short log those are the same
                // number — a padding that silently failed to reach past one `maxBytes` would leave
                // this test passing over the exact defect it was written for.
                val end = endOf(connection, EventTopics.USAGE)
                // DELIBERATELY THE BROKEN EXPRESSION, spelled out rather than through `endOf`: this
                // line IS the defect, kept here so the precondition measures the same thing the
                // mutation restores. (Written through the helper first — and a regex that replaced
                // every instance of this shape replaced the probe too, which made the assertion
                // compare the end against the end and pass regardless.)
                val brokenStart = Consumer(connection, TopicName(EventTopics.USAGE), handle.partitions.first())
                brokenStart.poll()
                val onePollIn = brokenStart.position
                assertTrue(
                    onePollIn < end,
                    "one poll from zero reached offset ${onePollIn.value} and the log ends at " +
                        "${end.value} — the padding did not exceed Consumer.DEFAULT_MAX_BYTES, so " +
                        "this test cannot tell the two starting points apart",
                )

                val job =
                    UsageChain(
                        brokerConnection,
                        ConsumeUsageUseCase(counters),
                        push,
                        cards,
                        roaming,
                        roamingCards,
                        clock,
                        json,
                    ).start(scope)

                try {
                    // ONE EVENT AFTER THE START, and it is what makes this test able to fail in the
                    // right direction. Without it a chain that read NOTHING AT ALL — a broken
                    // consumer, a wrong partition — would satisfy "the history was not replayed".
                    handle.send(event(units = 40).toByteArray(), subscriberId.toByteArray())
                    producer.flush()

                    assertNotNull(
                        remainingBecomes(9_960),
                        "the counter did not settle at 9960. Anything lower means the log's history " +
                            "was replayed (`B-108`); no movement at all means the chain read nothing",
                    )
                } finally {
                    job.cancel()
                }
                // AND THE METHOD RETURNS UNIT, which is not style. Written as `= runBlocking { … }`
                // ending on `assertNotNull`, this method returned a `Long` — and JUnit 5 does not
                // run a `@Test` that returns a value. It silently did not run, and the mutations
                // written to prove it works all passed. A test that cannot fail is worse than none.
                Unit
            } finally {
                brokerConnection.close()
            }
        }

    // THE RUNNING LOOP SURVIVES ITS BROKER GOING AWAY (`B-107`) — and this test exists because the
    // other three about reconnecting are about `BrokerConnection`, not about the loop that has to
    // use it. Every one of them would stay green over a `UsageConsumer` whose recovery had been
    // deleted, which is the shape of a mechanism written and never called.
    //
    // So this one calls `start()` and then breaks the connection underneath it, exactly as a
    // replaced broker pod does, and asks the only question that matters: does an event published
    // afterwards still reach the counter, with nobody restarting anything.
    @Test
    fun `a consumer whose connection dies keeps applying events without being restarted`() =
        runBlocking {
            val broker = BrokerConnection(BrokerHarness.host, BrokerHarness.port)
            // A SECOND CONNECTION FOR THE PUBLISHING SIDE, and it is load-bearing: breaking the
            // consumer's socket must not break the thing that proves the consumer came back. With
            // one connection this test could only ever say "everything is broken, then everything
            // works", which is a statement about `close()`.
            val writerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val writer = BrokerHarness.connect(writerScope)
            try {
                counters.grant(subscriberId, UsageCounter.Kind.DATA, 10_000)

                val producer = Producer(writer, writerScope)
                val handle = producer.topic(TopicName(EventTopics.USAGE))
                val partition = handle.partitions.first()
                val from =
                    writer
                        .metadata(listOf(TopicName(EventTopics.USAGE)))
                        .topics
                        .single()
                        .partitions
                        .single { it.partition == partition }
                        .highWatermark

                val job =
                    UsageConsumer(
                        broker,
                        ConsumeUsageUseCase(counters),
                        push,
                        cards,
                        roaming,
                        roamingCards,
                        clock,
                        json,
                    ).start(scope, partition, from)

                handle.send(usageEvent(100).toByteArray()).await()
                producer.flush()
                assertNotNull(
                    remainingBecomes(9_900),
                    "the consumer never applied the first event, so the break below proves nothing",
                )

                // The break. From here the consumer's own loop is the only thing that can recover.
                broker.connection.close()

                handle.send(usageEvent(250).toByteArray()).await()
                producer.flush()

                assertNotNull(
                    remainingBecomes(9_650),
                    "the consumer never came back: an event published after the connection broke was " +
                        "not applied, which is `B-107` exactly",
                )

                job.cancel()
            } finally {
                broker.close()
                writerScope.cancel()
            }
        }

    private fun usageEvent(megabytes: Long) = """{"subscriberId":"$subscriberId","kind":"data","units":$megabytes}"""

    // Polls the counter rather than the broadcast, because the subject is "the event was applied"
    // and the push is a consequence of it. Returns null on timeout so the caller names the failure.
    private suspend fun remainingBecomes(expected: Long): Long? =
        withTimeoutOrNull(20.seconds) {
            while (true) {
                val remaining =
                    counters.of(subscriberId).firstOrNull { it.kind == UsageCounter.Kind.DATA }?.remainingUnits
                if (remaining == expected) return@withTimeoutOrNull remaining
                kotlinx.coroutines.delay(100)
            }
            @Suppress("UNREACHABLE_CODE")
            null
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
                    endOf(connection, EventTopics.USAGE)

                val simulator =
                    TrafficSimulator(
                        BrokerHarness.broker(),
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
                        BrokerHarness.broker(),
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
                    BrokerHarness.broker(),
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
                    BrokerHarness.broker(),
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
                    BrokerHarness.broker(),
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

            val card = cards.of(low, clock.now())
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
                    BrokerHarness.broker(),
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
                    endOf(connection, EventTopics.USAGE)

                TrafficSimulator(
                    BrokerHarness.broker(),
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

    // THE PRODUCT'S OWN WORKER, WITHOUT THE MOCK — which is what `B-89` separated and what nothing
    // could do before it.
    //
    // `TrafficChain.start()` built the simulator and the consumer and started them together, and
    // nothing else constructed a `UsageConsumer`. So with `SIMULATE_TRAFFIC` off — the default, and
    // what the chart requires above one replica — no process in this build read the `usage` topic at
    // all: the broker accepted events and nobody applied them.
    //
    // This is the claim in one test: an event published BY HAND moves the counter and reaches an open
    // stream, with no simulator anywhere near it.
    @Test
    fun `an event published by hand is applied and pushed, with no simulator running`() =
        runBlocking {
            val connection = BrokerHarness.connect(scope)
            try {
                val listener = Channel<String>(Channel.UNLIMITED)
                broadcaster.subscribe(RealtimeStream.topicOf(subscriberId), listener)

                counters.grant(subscriberId, UsageCounter.Kind.DATA, 10_000)

                // THE PRODUCTION WRAPPER, pointed at the harness's broker — not a stand-in. `UsageChain`
                // takes what the composition root gives it, so a test that handed it something else
                // would be exercising a different assembly than the one that ships.
                val brokerConnection = BrokerConnection(BrokerHarness.host, BrokerHarness.port)
                val chain =
                    UsageChain(
                        brokerConnection,
                        ConsumeUsageUseCase(counters),
                        push,
                        cards,
                        roaming,
                        roamingCards,
                        clock,
                        json,
                    )
                val job = chain.start(scope)
                try {
                    // BY HAND, which is the point: nothing in this test produces traffic on a timer.
                    // A real integration publishes to this topic and expects the product to apply it.
                    val producer = Producer(connection, scope)
                    producer
                        .topic(TopicName(EventTopics.USAGE))
                        .send(event(units = 40).toByteArray(), subscriberId.toByteArray())
                    producer.flush()

                    val pushed =
                        withTimeoutOrNull(10_000) {
                            // The FIRST frame about this counter. The consumer pushes the card it has
                            // just recomputed, so the update carries the new number rather than a
                            // signal to refetch.
                            listener.receive()
                        }
                    assertNotNull(pushed, "nothing reached the open stream, so the consumer applied nothing")

                    assertEquals(
                        10_000L - 40,
                        counters.find(subscriberId, UsageCounter.Kind.DATA)?.remainingUnits,
                        "the counter did not move, so the event was accepted by the broker and applied by nobody",
                    )
                } finally {
                    job.cancel()
                    brokerConnection.close()
                }
            } finally {
                connection.close()
            }
        }

    private fun event(units: Long) = """{"subscriberId":"$subscriberId","kind":"data","units":$units}"""

    // WHERE A TOPIC ALREADY STANDS, asked of METADATA.
    //
    // Every one of these used to find that number by consuming from the start of the log and asking
    // the consumer where it had got to — which is one `maxBytes` in, and only equals the end while
    // the log is short. The shared broker in this JVM makes that a matter of what the other tests
    // published, and the moment one of them padded the log past a megabyte (`B-108`'s own test), two
    // tests started reading from the wrong place. It is the same mistake `UsageChain` shipped with,
    // and `PollIsNotAPositionTest` is what stops it coming back — including through a comment that
    // spells it out, which is why this one does not.
    private suspend fun endOf(
        connection: ru.workinprogress.booblik.net.client.BooblikConnection,
        topic: String,
    ) = connection
        .metadata(listOf(TopicName(topic)))
        .topics
        .single()
        .partitions
        .first()
        .highWatermark
}
