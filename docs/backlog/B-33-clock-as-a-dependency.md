---
id: B-33
title: "Time is injected, because four different deadlines depend on it"
status: done
priority: P1
size: XS
stage: stage-m0-wire
blocked_by: [B-01]
---

# B-33 — Time is injected, because four different deadlines depend on it

petich's `Suspend(ttl)` and its sweeper, a package's expiry, a counter's period and a tariff's billing
boundary are four separate clocks in disguise. Each one is a rule about *when*, and every one of them
is untestable while the answer comes from `Clock.System.now()` at the call site.

- **The decision and its reason.** A `Clock` is a constructor dependency of anything that computes a
  deadline, bound in Koin, replaced in tests. The alternative is a test that waits, and a suite with
  waits in it is a suite that gets slow, then flaky, then skipped — which is how a TTL bug reaches
  production through a green build.
- The rejected alternative is a global mutable test clock. It works and it makes two tests running in
  parallel share a variable.
- Not covered: time zones. Everything is instants; a billing boundary is computed in one fixed zone
  named in configuration, and that name is the only calendar concept in the system.

- AC ✅: `SuspendedSagaExpiryTest` runs a real saga that suspends for a confirmation with a
  five-minute TTL, sweeps *before* the deadline and asserts nothing happened, advances the fake clock
  six minutes, sweeps again and asserts the compensation ran. The TTL is five minutes and the test
  takes milliseconds. The sweep-before-the-deadline assertion is the one that matters: without it a
  sweeper that rolled everything back regardless of time would pass.
- AC ✅ **as a test rather than a grep**: `ClockUsageTest` reads the server sources and fails on any
  `Clock.System` outside `KonektClock.kt`, which is allowed by name. There is no signature to forbid
  this and no warning to enable, so the guard reads the source — and it guards itself in both
  directions: a renamed implementation would leave the allowance covering nothing, and the first
  assertion would still pass, having found no offenders because it found nothing at all.
- Also done: one clock for the domain and for petich. `KonektClock.asPetichClock()` adapts to
  petich's epoch-millis `PetichClock` rather than binding a second clock, so a test that moves time
  moves it for the deadline and for the sweep alike.
- Anchors: `server/src/main/kotlin/io/konekt/time/`,
  `server/src/test/kotlin/io/konekt/time/SuspendedSagaExpiryTest.kt`,
  `server/src/test/kotlin/io/konekt/time/ClockUsageTest.kt`.

Background: [research-stack](../research/research-stack.md) D18,
[research-architecture](../research/research-architecture.md) open question 2.
