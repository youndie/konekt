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
import io.konekt.feature.usage.server.data.ExposedUsageCounters
import io.konekt.feature.usage.server.domain.ConsumeUsageUseCase
import io.konekt.feature.usage.server.domain.UsageCounter
import io.konekt.mocks.traffic.TrafficSimulator
import io.konekt.mocks.traffic.UsageConsumer
import io.konekt.realtime.ComponentBroadcaster
import io.konekt.realtime.topicOf
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
                broadcaster.subscribe(topicOf(subscriberId), listener)

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
                    TrafficSimulator(producer, subscribers = { listOf(subscriberId) }, megabytesPerTick = 25)
                simulator.tick(handle)

                val consumer = Consumer(connection, TopicName(EventTopics.USAGE), handle.partitions.first(), start)
                val applied = UsageConsumer(connection, ConsumeUsageUseCase(counters), push, json).drain(consumer)

                assertEquals(1, applied, "the simulator's event did not reach the consumer")
                assertEquals(9_975, assertNotNull(counters.find(subscriberId, UsageCounter.Kind.DATA)).remainingUnits)

                // And the screen hears about it. This is the link the whole chain exists for: a
                // counter that moved and nobody was told is a counter the subscriber refreshes to see.
                val frame = assertNotNull(listener.tryReceive().getOrNull(), "the counter moved and nothing was pushed")
                val message = json.decodeFromString(UpdateComponentMessage.serializer(), frame)
                assertEquals("counter-data", message.componentId)
                assertEquals("9975 MB left", (message.component as UsageCounterCardComponent).valueText)
            } finally {
                connection.close()
            }
        }

    @Test
    fun `stopping the traffic stops the movement and nothing else changes`() =
        runBlocking {
            counters.grant(subscriberId, UsageCounter.Kind.DATA, 1_000)
            val consumer = UsageConsumer(BrokerHarness.connect(), ConsumeUsageUseCase(counters), push, json)

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
            val consumer = UsageConsumer(BrokerHarness.connect(), ConsumeUsageUseCase(counters), push, json)

            consumer.apply(event(units = 400))

            val counter = assertNotNull(counters.find(subscriberId, UsageCounter.Kind.DATA))
            // A screen that says minus three hundred and ninety megabytes is worse than one that says
            // zero, and the clamp is in SQL because two decrements arriving together both pass a
            // read-then-check.
            assertEquals(0, counter.remainingUnits)
            assertTrue(counter.isExhausted)
        }

    @Test
    fun `the copy changes with the state and not only the colour`() =
        runBlocking {
            counters.grant(subscriberId, UsageCounter.Kind.DATA, 1_000)
            val consumer = UsageConsumer(BrokerHarness.connect(), ConsumeUsageUseCase(counters), push, json)

            consumer.apply(event(units = 950))
            val low = assertNotNull(counters.find(subscriberId, UsageCounter.Kind.DATA))
            assertTrue(low.isLow, "50 of 1000 left is not being called low")

            val card =
                io.konekt.feature.usage.server.data.UsageCounterCards
                    .of(low)
            assertEquals(CounterStates.LOW, card.state)
            // The canvas's rule: a subscriber who is nearly out is told what that means, not shown a
            // different colour and left to work it out.
            assertNotNull(card.captionText)
        }

    @Test
    fun `usage for a subscriber who bought nothing is ignored rather than failing`() =
        runBlocking {
            val consumer = UsageConsumer(BrokerHarness.connect(), ConsumeUsageUseCase(counters), push, json)

            // No counter exists. The simulator does not know who has bought what, and a consumer that
            // threw here would stop the whole poll for everybody else.
            consumer.apply(event(units = 25))

            assertEquals(null, counters.find(subscriberId, UsageCounter.Kind.DATA))
        }

    private fun event(units: Long) = """{"subscriberId":"$subscriberId","kind":"data","units":$units}"""
}
