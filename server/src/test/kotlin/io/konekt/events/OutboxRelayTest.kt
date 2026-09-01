package io.konekt.events

import io.konekt.testing.PostgresHarness
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import ru.workinprogress.booblik.TopicName
import ru.workinprogress.booblik.net.client.Consumer
import ru.workinprogress.booblik.net.client.Producer
import ru.workinprogress.petich.outbox.OutboxPublisher
import ru.workinprogress.petich.outbox.OutboxRecord
import ru.workinprogress.petich.outbox.OutboxRelayWorker
import ru.workinprogress.petich.postgres.ExposedOutboxRepository
import ru.workinprogress.petich.postgres.OutboxEventsTable
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

// The bridge petich deliberately does not provide, against a real outbox and a real broker.
//
// petich gives at-least-once delivery with backoff and dead-lettering and says outright that the
// transport is the application's. What is tested here is the seam: that a row written by a saga
// reaches a topic, that a broker which is down delays a row rather than losing it, and that a
// redelivery is recognisable as one.
@OptIn(ExperimentalUuidApi::class)
class OutboxRelayTest {
    private val table = OutboxEventsTable()
    private val outbox = ExposedOutboxRepository(PostgresHarness.database, table)

    @BeforeTest
    fun clean() {
        PostgresHarness.truncateAll()
    }

    @Test
    fun `a row in the outbox reaches its topic`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val connection = BrokerHarness.connect(scope)
            try {
                val producer = Producer(connection, scope)
                val orderId = Uuid.random().toString()
                val handle = producer.topic(TopicName(EventTopics.ORDERS))
                // Where the topic stands before this test writes to it. The broker is shared by every
                // test in this class, so "there is a record on the topic" is only meaningful relative
                // to where it already was.
                val before =
                    endOf(connection, EventTopics.ORDERS)

                writeOutboxRow(id = "$orderId:purchase.completed", type = "purchase.completed", orderId = orderId)

                OutboxRelayWorker(outbox, BooblikOutboxPublisher(BrokerHarness.broker())).tick()
                producer.flush()

                val consumer = Consumer(connection, TopicName(EventTopics.ORDERS), handle.partitions.first())
                consumer.seek(before)
                val payloads = consumer.poll().records.map { String(it) }

                assertTrue(payloads.any { orderId in it }, "the event did not reach the topic: $payloads")
                assertEquals(0, pendingCount(), "the row was delivered and left pending")
            } finally {
                connection.close()
                scope.cancel()
            }
        }

    @Test
    fun `a broker that is down delays the row rather than losing it`() =
        runBlocking {
            // A publisher that refuses once. The important half is not that the first tick fails —
            // it is that the row is STILL THERE afterwards, which is the difference between an outbox
            // and a fire-and-forget call in a saga.
            var attempts = 0
            val flaky =
                OutboxPublisher {
                    attempts++
                    if (attempts == 1) throw IllegalStateException("the broker is down")
                }

            // A test time source, so the backoff between attempts is moved rather than waited out.
            // With the real one this test would sleep a second to prove a retry happens.
            val time = TestTimeSource()
            val relay = OutboxRelayWorker(outbox, flaky, timeSource = time)

            writeOutboxRow(id = "order-1:purchase.completed", type = "purchase.completed", orderId = "order-1")

            relay.tick()
            assertEquals(1, pendingCount(), "a failed publish dropped the row")
            assertEquals(1, attempts)

            // Inside the backoff the row is deliberately not retried, so a broker that is down is not
            // hammered by a poller.
            relay.tick()
            assertEquals(1, attempts, "the relay retried inside its own backoff")

            time += 10.seconds
            relay.tick()

            assertEquals(2, attempts)
            assertEquals(0, pendingCount(), "the row was not delivered once the broker came back")
        }

    @Test
    fun `a redelivered event is recognisable as the same event`() =
        runBlocking {
            // At-least-once means the same event arrives twice, and a relay that tried to be
            // exactly-once would be inventing consensus. What makes the duplicate harmless is that
            // its id is stable — `<orderId>:<type>` — so a consumer keyed on it applies the second
            // copy to nothing.
            //
            // THE REDELIVERY IS SIMULATED THE WAY IT REALLY HAPPENS: the publish lands and the
            // "delivered" mark does not, which is what a crash between the two leaves behind. An
            // earlier version of this test inserted a second row with a doctored id, which made the
            // assertion trivially false — a duplicate with a different id is not a redelivery, it is
            // a different event, and testing it proves the opposite of the point.
            val delivered = mutableListOf<OutboxRecord>()
            val relay = OutboxRelayWorker(SwallowsFirstMark(outbox), OutboxPublisher { delivered += it })

            writeOutboxRow(id = "order-2:purchase.completed", type = "purchase.completed", orderId = "order-2")

            relay.tick()
            relay.tick()

            assertEquals(2, delivered.size, "the row was not redelivered, so this proves nothing")
            assertEquals(delivered[0].id, delivered[1].id, "two deliveries of one event carry different ids")
            assertEquals(delivered[0].payload, delivered[1].payload)
            assertEquals("order-2:purchase.completed", delivered[0].id)
        }

    // A repository that loses the first "delivered" mark, which is exactly what a process dying
    // between the publish and the mark leaves behind — and the only way the outbox produces a
    // duplicate at all.
    private class SwallowsFirstMark(
        private val delegate: ru.workinprogress.petich.outbox.OutboxRepository,
    ) : ru.workinprogress.petich.outbox.OutboxRepository by delegate {
        private var swallowed = false

        override suspend fun markDelivered(id: String) {
            if (!swallowed) {
                swallowed = true
                return
            }
            delegate.markDelivered(id)
        }
    }

    private fun pendingCount(): Int =
        transaction(PostgresHarness.database) {
            table
                .selectAll()
                .where { table.status eq "PENDING" }
                .count()
                .toInt()
        }

    private fun writeOutboxRow(
        id: String,
        type: String,
        orderId: String,
    ) {
        transaction(PostgresHarness.database) {
            table.insert {
                it[table.id] = id
                it[table.type] = type
                it[payload] = Json.encodeToString(mapOf("orderId" to orderId, "planId" to "tr-10gb-30d"))
                it[createdAt] = 0
            }
        }
    }

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
