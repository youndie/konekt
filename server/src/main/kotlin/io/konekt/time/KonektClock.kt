package io.konekt.time

import ru.workinprogress.petich.PetichClock
import kotlin.time.Clock
import kotlin.time.Instant

// Time is a dependency here, never a call to the system clock at the point of use.
//
// Four separate rules in this product are rules about *when*: petich's Suspend(ttl) and the sweeper
// that enforces it, a package's expiry, a counter's period, and a tariff's billing boundary. Each is
// untestable while the answer comes from the system — and the alternative, a test that waits, is how
// a suite becomes slow, then flaky, then skipped. A TTL bug then reaches production through a green
// build, which is the specific failure this exists to prevent.
fun interface KonektClock {
    fun now(): Instant
}

// The one implementation that reads the machine. It is bound in the composition root and named here
// so that the source guard in ClockUsageTest has exactly one place to allow.
object SystemClock : KonektClock {
    override fun now(): Instant = Clock.System.now()
}

// petich takes its own one-method clock, in epoch millis, because a deadline is compared across rows
// in a database and survives a process restart — a monotonic mark would mean nothing there. Adapting
// rather than binding a second clock is what keeps the saga sweeper and the domain on one notion of
// now: a test that moves time moves it for both.
fun KonektClock.asPetichClock(): PetichClock = PetichClock { now().toEpochMilliseconds() }
