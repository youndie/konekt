---
id: B-33
title: "Time is injected, because four different deadlines depend on it"
status: open
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

- AC: the compensation-on-TTL-expiry test advances a fake clock and never sleeps.
- AC: `grep` finds no `Clock.System.now()` outside the composition root and the clock binding.
- Anchors: `server/src/main/kotlin/io/konekt/time/`.

Background: [research-stack](../research/research-stack.md) D18,
[research-architecture](../research/research-architecture.md) open question 2.
