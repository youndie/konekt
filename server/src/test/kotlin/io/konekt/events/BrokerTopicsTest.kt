package io.konekt.events

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import ru.workinprogress.booblik.PartitionId
import ru.workinprogress.booblik.TopicName
import ru.workinprogress.booblik.net.client.Consumer
import ru.workinprogress.booblik.net.client.Producer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The acceptance of B-13: the three topics the compose file declares are really there, checked by
// writing to each and reading back what was written.
//
// Asking the broker for metadata would be a weaker claim — a topic can be listed and unusable — so
// this does the round trip. It is also the only way to notice a topic declared with zero partitions,
// which metadata reports happily and a producer cannot write to.
class BrokerTopicsTest {
    @Test
    fun `every topic the deployment declares can be written to and read from`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val connection = BrokerHarness.connect(scope)

            try {
                val producer = Producer(connection, scope)

                EventTopics.all.forEach { name ->
                    val topic = TopicName(name)

                    // The partitions come from the broker rather than from a number we pass, which is
                    // what makes this a check on the broker's configuration rather than on our own
                    // idea of it.
                    val handle = producer.topic(topic)
                    assertTrue(handle.partitions.isNotEmpty(), "$name exists with no partitions")

                    val body = "probe:$name".toByteArray()
                    handle.send(body).await()
                    producer.flush()

                    val consumer = Consumer(connection, topic, handle.partitions.first())
                    val records = consumer.poll().records

                    assertTrue(records.isNotEmpty(), "$name accepted a record and returned nothing")
                    assertEquals("probe:$name", String(records.last()))
                }
            } finally {
                connection.close()
                scope.cancel()
            }
        }

    @Test
    fun `the topics this server routes to are the topics the compose file declares`() {
        // Two halves of one decision in two files, held together here. A type routed to a topic the
        // broker does not have is a publish that fails forever — booblik creates nothing on demand —
        // and the failure surfaces as a stuck outbox rather than as a missing topic.
        val declared = BrokerHarness.TOPICS.split(",").map { it.substringBefore(':') }

        assertEquals(declared.toSet(), EventTopics.all.toSet())
    }

    @Test
    fun `an event type with no topic is refused rather than defaulted`() {
        // A default here means an event arriving somewhere nobody is listening, which is the quietest
        // failure this design has.
        val failure = runCatching { EventTopics.topicFor("something.nobody.declared") }.exceptionOrNull()

        assertTrue(failure is IllegalStateException, "an unrouted event type was answered with $failure")
    }
}
