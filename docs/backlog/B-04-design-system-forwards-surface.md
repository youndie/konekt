---
id: B-04
title: "The design system must keep its surface roles after the theme arrives"
status: open
priority: P0
size: S
stage: stage-m0-wire
blocked_by: [B-01]
---

# B-04 — The design system must keep its surface roles after the theme arrives

`RemoteThemeDesignSystem` overrides `resolveColor` and `resolveTypography` and inherits the interface
default for `resolveSurface`, without delegating to the fallback it holds (research §1.3). An
application that customises surfaces and then wraps itself in the remote theme loses every one of
them the moment the theme lands: the first frame is right, the second is Material's pill and
`OutlinedTextField`'s border. Nothing throws and nothing logs.

- **The decision and its reason.** konekt's design system holds `RemoteThemeDesignSystem` rather than
  being held by it — colour and typography delegate outwards, `resolveSurface` stays ours. Guarded by
  a screenshot test that draws one screen twice, before and after the theme, failing on any difference
  outside colour and type.
- The rejected alternative is waiting for the upstream fix. The composition root is written once and
  the defect is invisible once there is enough styling to hide in; the wrapper costs a dozen lines.
- Not covered: removing the inversion when [U1](../research/research-upstream-proposals.md#u1) lands.
  That is a separate item, and the comment in the code names the issue so it can be found.

- AC: with a theme describing colours only, `resolveSurface(Field)` still answers konekt's borderless
  field, asserted in a unit test.
- AC: the before/after screenshot pair is byte-identical outside colour and typography.
- Anchors: `client/src/commonMain/kotlin/io/konekt/theme/KonektDesignSystem.kt`,
  `client/src/commonTest/kotlin/io/konekt/theme/`.

Background: [research-architecture](../research/research-architecture.md) §1.3, Risk 4.
