package io.konekt.events

import io.github.youndie.booblik.TopicName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

// WHAT A CALLER IS TOLD WHEN ITS RECORD IS DISCARDED, and whether this build recognises it.
//
// `Producer` drains its pending batches in the `finally` of its run-loop job — so it happens when the
// loop ends for ANY reason, the broker dying on its own included, and not only when something closes
// it. Each pending answer is completed exceptionally, and the caller awaiting it sees that exception.
//
// `BrokerConnection.isFinished` is the one place this build decides whether a failure means THIS
// CONNECTION IS FINISHED rather than THIS CALL FAILED, and every recovery hangs off it.
//
// WRITTEN TO CATCH A DEFECT THAT TURNED OUT NOT TO EXIST, and kept because the property is worth
// pinning. `drainPending` completes each pending answer with `ConnectionClosedException`, which
// extends `IllegalStateException` and is therefore NOT one of the two types `isFinished` matches —
// so by reading, a discarded record should leave nobody reconnecting. It does not: measured here
// with a five-second linger, which guarantees the record is still in `pending` when the loop ends,
// the caller gets `ClosedSendChannelException` every time. The delivery attempt fails first and
// completes the answer, and `drainPending`'s `completeExceptionally` on an already-completed
// deferred is a no-op.
//
// So the classification is right today for a reason that is not in `isFinished`'s own list, and a
// change in booblik's ordering would make it wrong silently. That is what this asserts.
class BrokerDiscardedRecordTest {
    @Test
    fun `a record discarded by the producer tells the caller the connection is finished`() =
        runBlocking {
            val broker = BrokerHarness.broker()
            val topic = TopicName(EventTopics.all.first())

            val failure =
                try {
                    val handle = broker.producer.topic(topic)

                    // Pending on purpose: handed over and not awaited, so it is still in the
                    // accumulator when the socket goes.
                    val answer = broker.producer.send(topic, handle.partitions.first(), "discarded".toByteArray())

                    // THE SOCKET, NOT THE HOLDER. Closing the holder would flush first and the record
                    // would land — which is the fix in #31 and the opposite of what this needs. This
                    // is the broker going away underneath a producer that still has work.
                    broker.connection.close()

                    try {
                        answer.await()
                        null
                    } catch (cancelled: CancellationException) {
                        // Ahead of the catch below, because `CancellationException` IS an `Exception`:
                        // swallowing it would turn a cancelled await into a value and leave this
                        // coroutine running after something asked it to stop. What this test wants is
                        // the failure the producer put on the answer, not the one the test framework
                        // put on the test.
                        throw cancelled
                    } catch (thrown: Exception) {
                        thrown
                    }
                } finally {
                    broker.close()
                }

            assertTrue(failure != null, "the record was neither sent nor failed — the producer answered for it")
            assertTrue(
                BrokerConnection.isFinished(failure),
                "a discarded record raises ${failure::class.qualifiedName}, which this build does not " +
                    "treat as a finished connection — so nothing reconnects on it",
            )
        }
}
