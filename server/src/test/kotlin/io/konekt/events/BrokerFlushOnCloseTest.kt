package io.konekt.events

import io.github.youndie.booblik.TopicName
import io.github.youndie.booblik.net.client.Consumer
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// WHAT A CLOSE DOES TO RECORDS THAT ARE STILL IN THE ACCUMULATOR.
//
// `Producer` is an accumulator by design — it collects for `lingerMillis` before sending, which is
// the single largest factor in this broker — so at any instant there may be records that have been
// handed over and not yet written. Closing decides what becomes of them, and booblik's JVM client
// decides to throw them away: `drainPending()` completes every pending batch EXCEPTIONALLY with
// `ConnectionClosedException` instead of sending it. Its native client does the opposite, beginning
// the same method with `sendAll()` under a comment reading "dropping it would be silent loss". Filed
// as youndie/booblik#68; until that is settled, the flush has to happen on this side.
//
// The path that matters is not shutdown. `closeQuietly` is reached from `reconnect` as well, once
// per broker reconnect — which is exactly when the broker pod was replaced and the records
// describing it are the ones worth keeping.
class BrokerFlushOnCloseTest {
    @Test
    fun `a record handed over but not yet acknowledged survives the connection being closed`() =
        runBlocking {
            val broker = BrokerHarness.broker()
            val topic = TopicName(EventTopics.all.first())
            val body = "flush-on-close:${System.nanoTime()}"

            val partitions =
                try {
                    val handle = broker.producer.topic(topic)

                    // NOT AWAITED, and that is the whole fixture. Awaiting would mean the broker has
                    // already answered and there would be nothing left in the accumulator to lose —
                    // which is why this test cannot be written with the ordinary publish path, where
                    // every caller in this build does await.
                    broker.producer.send(topic, handle.partitions.first(), body.toByteArray())

                    handle.partitions
                } finally {
                    // The moment under test. Before the flush was added this discarded the record.
                    broker.close()
                }

            // READ BACK ON A CONNECTION OF ITS OWN, because the one above is now closed. Asserting on
            // the deferred instead would ask whether the CALLER was told, which is a different
            // question — and one the caller can answer by retrying. What cannot be recovered is a
            // record the broker never received.
            val reader = BrokerHarness.connect()
            try {
                val records = Consumer(reader, topic, partitions.first()).poll().records
                assertTrue(records.isNotEmpty(), "the topic is empty — the record never reached the broker")
                assertEquals(body, String(records.last()), "the last record on the topic is not the one handed over")
            } finally {
                reader.close()
            }
        }
}
