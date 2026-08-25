package io.konekt.domain

import kotlin.coroutines.cancellation.CancellationException

// `runCatching` for suspending code, and the only reason it exists is the one line in the middle.
//
// Plain `runCatching` catches `Throwable`, and a cancelled coroutine is cancelled by throwing
// `CancellationException` through it. Swallowing that turns cancellation into an ordinary failure:
// the coroutine keeps running, the caller's `withTimeout` does not stop anything, and a client that
// disconnected is still being served. The symptom is a request that will not stop, which nobody
// attributes to an error-handling helper.
//
// Used everywhere a use case wraps its work. `runCatching` in suspending code is a defect in this
// repository, and ErrorContractTest is what says so.
suspend inline fun <T> suspendRunCatching(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        Result.failure(failure)
    }
