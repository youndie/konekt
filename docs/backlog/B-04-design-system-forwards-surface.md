---
id: B-04
title: "Guard that the design system keeps its surface roles after the theme arrives"
status: open
priority: P0
size: S
stage: stage-m0-wire
blocked_by: [B-01]
---

# B-04 — Guard that the design system keeps its surface roles after the theme arrives

`RemoteThemeDesignSystem` overrides `resolveColor` and `resolveTypography` and inherits the interface
default for `resolveSurface`, without delegating to the fallback it holds (research §1.3). An
application that customises surfaces and then wraps itself in the remote theme loses every one of
them the moment the theme lands: the first frame is right, the second is Material's pill and
`OutlinedTextField`'s border. Nothing throws and nothing logs.

**Fixed upstream before this item started.** kompot#80 closed on 2026-08-25 and `0.31.0.74` carries
`override fun resolveSurface(role) = fallback.resolveSurface(role)`. The inverted wrapping this item
was going to build is therefore **not built** — the composition root is written the ordinary way, the
way every example in the readme writes it.

- **What survives is the guard.** The screenshot test draws one screen twice, before and after the
  theme arrives, and fails on any difference outside colour and typography. A test written for a
  fixed bug is what notices the regression, and this one costs a golden pair.
- The rejected alternative is dropping the item because the bug is gone. The bug was in the toolkit
  for at least one release without anyone noticing, which is the argument for the guard rather than
  against it.
- Not covered: the other two hooks. Colour and typography are what `RemoteThemeDesignSystem` is for
  and were never in doubt.

- AC: with a theme describing colours only, `resolveSurface(Field)` still answers konekt's borderless
  field, asserted in a unit test — against the toolkit, not against a local wrapper.
- AC: the before/after screenshot pair is byte-identical outside colour and typography.
- Anchors: `client/src/commonMain/kotlin/io/konekt/theme/KonektDesignSystem.kt`,
  `client/src/commonTest/kotlin/io/konekt/theme/`.

Background: [research-architecture](../research/research-architecture.md) §1.3, Risk 4.
