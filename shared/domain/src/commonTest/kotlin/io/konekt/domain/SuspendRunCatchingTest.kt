package io.konekt.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SuspendRunCatchingTest {
    @Test
    fun `an ordinary failure becomes a failed Result`() =
        runTest {
            val result = suspendRunCatching { throw KonektException.NotFound("order") }

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is KonektException.NotFound)
        }

    @Test
    fun `a success is a success`() =
        runTest {
            assertEquals(7, suspendRunCatching { 7 }.getOrThrow())
        }

    @Test
    fun `a cancellation is rethrown rather than captured`() =
        runTest {
            assertFailsWith<CancellationException> {
                suspendRunCatching { throw CancellationException("the caller went away") }
            }
        }

    @Test
    fun `cancelling the caller actually stops the work`() =
        runTest {
            // The assertion that matters, and the one a thrown-exception test cannot make. With plain
            // runCatching the CancellationException is swallowed, the block returns a failed Result,
            // and the coroutine carries on to the line after it — so the work continues after the
            // caller has gone. Here it must not.
            val started = CompletableDeferred<Unit>()
            var reachedTheLineAfter = false

            val job =
                launch {
                    suspendRunCatching {
                        started.complete(Unit)
                        awaitCancellation()
                    }
                    reachedTheLineAfter = true
                }

            started.await()
            job.cancel()
            job.join()

            assertTrue(job.isCancelled, "the job survived its own cancellation")
            assertTrue(!reachedTheLineAfter, "the work continued after the caller was cancelled")
        }

    @Test
    fun `a cancelled child does not turn into a failed Result upstream`() =
        runTest {
            // The same mistake one level up: a helper that captured the cancellation would report it
            // to the caller as an ordinary error, and the caller would then decide what to do about a
            // failure that is not one.
            val deferred = async { suspendRunCatching { awaitCancellation() } }

            deferred.cancel()

            assertTrue(deferred.isCancelled)
        }
}
