package io.konekt.time

import kotlin.time.Duration
import kotlin.time.Instant

// A clock a test moves by hand.
//
// An instance per test rather than a global mutable one: a shared test clock is a variable two
// parallel tests write to, and the failure that produces depends on execution order — which is the
// worst kind of flake to read.
class MutableTestClock(
    private var instant: Instant = Instant.fromEpochMilliseconds(1_700_000_000_000),
) : KonektClock {
    override fun now(): Instant = instant

    fun advance(by: Duration) {
        instant += by
    }
}
