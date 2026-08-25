package io.konekt.time

import kotlin.time.Clock
import kotlin.time.Instant

// Time is a dependency here, never a call to the system clock at the point of use.
//
// Four separate rules in this product are rules about *when*: petich's Suspend(ttl) and the sweeper
// that enforces it, an OTP's expiry and lockout, a package's life, and a tariff's billing boundary.
// Each is untestable while the answer comes from the system — and the alternative, a test that waits,
// is how a suite becomes slow, then flaky, then skipped. A TTL bug then reaches production through a
// green build, which is the specific failure this exists to prevent.
//
// IT LIVES IN THE SHARED DOMAIN rather than in :server, and that placement was a correction: a
// feature's -server-domain module must not depend on :server, because :server is what composes the
// features. Putting the clock where the domain can see it is what keeps the dependency pointing
// inwards.
//
// A `fun interface`, so a test that needs a fixed instant writes `KonektClock { instant }` and needs
// no shared fixture.
fun interface KonektClock {
    fun now(): Instant
}

// The one implementation that reads the machine. Named here so the source guard has exactly one place
// to allow.
object SystemClock : KonektClock {
    override fun now(): Instant = Clock.System.now()
}
