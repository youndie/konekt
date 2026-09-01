package io.konekt.events

import io.konekt.mocks.traffic.UsageConsumer
import kotlinx.coroutines.runBlocking
import ru.workinprogress.booblik.TopicName
import ru.workinprogress.booblik.net.client.Consumer
import ru.workinprogress.petich.outbox.OutboxRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

// THE PROCESS SURVIVES ITS BROKER BEING REPLACED — which it did not, and `B-107` is what it cost.
//
// `BooblikConnection` opens one `SocketChannel` in its constructor and never dials again; the
// position and the socket both live on the client, which is the same decision that removes the group
// coordinator. Held for the life of a process, that turned a routine event — a chart change, a node
// drain, an image bump — into a permanent one: `EOFException: broker closed the connection` five
// times a second for as long as anybody watched, `conns 0` on the broker because nothing ever
// dialled it again, and live usage dead until somebody restarted the pod.
//
// THE SUBJECT HERE IS A DEAD SOCKET, not a restarted container. Killing the broker in a test would
// need a container of its own — testcontainers hands out a NEW mapped port on restart, so the
// address the client is holding would be wrong for a reason production never has, and the test would
// then be about testcontainers. Closing the connection underneath its user reproduces exactly the
// state that matters and nothing else.
class BrokerReconnectTest {
    private val broker get() = BrokerConnection(BrokerHarness.host, BrokerHarness.port)

    @Test
    fun `a connection killed underneath its user is replaced, once, by whoever finds it first`() =
        runBlocking {
            val connection = broker
            try {
                val dead = connection.connection
                val generation = connection.generation

                connection.connection.close()

                val next = connection.reconnect(generation)

                assertNotEquals(generation, next, "the generation did not move, so nothing was replaced")
                assertNotEquals(next, 0, "a replacement must not reuse the first generation")
                assertTrue(connection.connection !== dead, "the broken connection is still the live one")

                // The second caller holds the SAME stale generation and must find the work done
                // rather than throw away the connection the first one just opened. Two consumers
                // sharing a broker find the same dead socket within one poll interval of each other,
                // so this race is the ordinary case rather than an exotic one.
                val fresh = connection.connection
                assertEquals(next, connection.reconnect(generation), "a second caller reconnected again")
                assertSame(fresh, connection.connection, "the second call replaced a healthy connection")
            } finally {
                connection.close()
            }
        }

    @Test
    fun `a consumer resumes from its own position on the connection that replaced the broken one`() =
        runBlocking {
            val connection = broker
            val topic = TopicName(EventTopics.USAGE)
            try {
                val handle = connection.producer.topic(topic)
                val partition = handle.partitions.first()

                // Where this partition is BEFORE anything of ours is written, because the harness's
                // broker is shared and another test may have put records here already. A test that
                // assumed an empty log would pass or fail by test ordering.
                val start =
                    connection.connection
                        .metadata(listOf(topic))
                        .topics
                        .single()
                        .partitions
                        .single { it.partition == partition }
                        .highWatermark

                repeat(2) { handle.send("before-the-break-$it".toByteArray()).await() }
                connection.producer.flush()

                var generation = connection.generation
                var consumer = Consumer(connection.connection, topic, partition, start)

                val before = consumer.poll().records.map { String(it) }
                assertEquals(
                    listOf("before-the-break-0", "before-the-break-1"),
                    before,
                    "the records before the break are not the ones that were published",
                )
                val resumeAt = consumer.position

                // The break, and it must be a REAL one: the consumer is holding this socket and the
                // next poll on it is the failure the loop has to survive.
                connection.connection.close()
                val broken =
                    assertFailsWith<Exception>("a poll on a closed connection must fail") { consumer.poll() }

                // AND THE LOOP MUST RECOGNISE IT. This is `ClosedSendChannelException`, not the
                // `EOFException` a departed broker raises — closing from this side is the only way a
                // test reaches the same state without a container of its own — so a recovery that
                // matched on `IOException` alone would work in production and be exercised by
                // nothing. It was written that way first, and this line is what said so.
                assertTrue(
                    BrokerConnection.isFinished(broken),
                    "the loop would not treat ${broken::class.simpleName} as a finished connection",
                )

                // What `UsageConsumer` does with that failure, in the same order.
                generation = connection.reconnect(generation)
                consumer = Consumer(connection.connection, topic, partition, resumeAt)

                // PUBLISHED AFTER THE BREAK, on the connection that replaced the broken one — so
                // this asserts both halves at once: the producer works again, and the consumer
                // resumed at its own position rather than at the end or at the start.
                //
                // A FRESH HANDLE, and the first draft of this test failed for want of one. A
                // `TopicHandle` wraps the `Producer` it was made from, so a reconnect makes every
                // one already in hand as dead as the socket underneath it. That is precisely why
                // `BooblikOutboxPublisher` clears its handle cache on a generation change rather
                // than keeping handles for the life of the process.
                val fresh = connection.producer.topic(topic)
                repeat(2) { fresh.send("after-the-break-$it".toByteArray()).await() }
                connection.producer.flush()

                val after = consumer.poll().records.map { String(it) }

                assertEquals(
                    listOf("after-the-break-0", "after-the-break-1"),
                    after,
                    "the consumer did not resume at its own position — it read: $after",
                )
                assertEquals(generation, connection.generation, "the generation moved again on its own")
            } finally {
                connection.close()
            }
        }

    // AND THE PUBLISHER, which had the same defect and hid it better. The outbox relay's contract is
    // to leave a row pending and try later, so a producer bound to a dead socket produces an outbox
    // that never drains — indistinguishable from an outbox with nothing in it.
    @Test
    fun `the outbox publisher publishes again after the connection under it is replaced`() =
        runBlocking {
            val connection = broker
            try {
                val publisher = BooblikOutboxPublisher(connection)

                publisher.publish(record("before"))

                connection.connection.close()

                // The relay's own retry, in miniature: this attempt fails and the row stays pending.
                assertFailsWith<Exception>("a publish on a dead socket must not report success") {
                    publisher.publish(record("during"))
                }

                // And the next one runs on the connection that failure asked for. Nothing here
                // reconnects on the publisher's behalf — if it did, this test would pass over a
                // publisher that had learnt nothing.
                publisher.publish(record("after"))
            } finally {
                connection.close()
            }
        }

    // A type the router actually knows: `EventTopics.topicFor` refuses an unknown one, and the
    // first draft of this test failed on that rather than on anything about reconnecting.
    private fun record(
        id: String,
        type: String = "purchase.completed",
    ) = OutboxRecord(
        id = id,
        type = type,
        payload = """{"orderId":"$id"}""",
        retryCount = 0,
    )
}
