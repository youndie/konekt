---
id: B-04
title: "Guard that the design system keeps its surface roles after the theme arrives"
status: done
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
was going to build is therefore **not built** — the composition root is written the ordinary way.

- **What survives is the guard**, and it is now two: a unit guard that reads the design system's
  answers through the theme, and a rendered one that draws the toolkit's real renderers before and
  after the theme arrives and compares the frames.
- The rejected alternative is dropping the item because the bug is gone. The bug was in the toolkit
  for at least one release without anyone noticing, which is the argument for the guard.
- Not covered: the other two hooks. Colour and typography are what `RemoteThemeDesignSystem` is for.

- AC OK: with a theme describing colours only, `resolveSurface(Field)` still answers konekt's
  borderless field — asserted against the toolkit, per role rather than in aggregate, and with a
  positive control (`assertNotSame`) because `rememberKompotDesignSystem` returns the fallback
  unchanged when the theme is null and every assertion would otherwise pass on a run with no theme.
- AC OK, **in a different form than written**: the before/after pair is compared **inside one run**
  rather than against a committed golden. A golden pair compares this machine with a recording of
  this machine, needs a bundled font to travel, and — since goldens are not wired into `check` —
  proves nothing until somebody runs the task that reads them. The in-run comparison needs none of
  that and runs in `build`. What it cannot catch is konekt's own surfaces drifting, since both frames
  would move together; that is the golden pair's job and it is now written into `B-28`.

**Three things this cost that were not in the item.**

**The client module did not exist, and could not be built the way every other module is.** kompot's
Compose half publishes no iOS artefact at all — six modules, `-android`/`-desktop`/`-wasm-js` and
nothing else — while the protocol half ships the three iOS targets. So `:client` is JVM-only and does
not use `konekt.multiplatform`. Reported as
[youndie/kompot#84](https://github.com/youndie/kompot/issues/84); see
[research-architecture](../research/research-architecture.md) §1.14, and note that even a fix reaches
two iOS targets rather than three, because Compose stopped publishing `iosX64`.

**Newest is the wrong default for Compose here.** Building against `1.12.0` puts foundation `1.12.0`
beside the material3 `1.11.0-alpha07` the toolkit carries, and the pair throws `AbstractMethodError`
at render time — not at resolution, not at compile.
[research-stack](../research/research-stack.md) §1.10.

**The first version of the rendered guard proved nothing, and its own control said so.** Two things
were wrong and neither was visible: brand A's button is a pill and so is Material's default, so a
tree of buttons cannot tell a design system that answers from one that does not; and the fixture
painted an opaque background, so the alpha channel — which the comparison reads as the silhouette —
was 1.0 everywhere in both frames. The fix is a `read_only_field` in the tree and no background on
it. The control that caught this is now a permanent test: it renders the *old* behaviour on purpose
and fails if the comparison cannot see it.

- Anchors: `client/src/commonMain/kotlin/io/konekt/client/theme/KonektDesignSystem.kt`,
  `client/src/jvmTest/kotlin/io/konekt/client/theme/`.

Background: [research-architecture](../research/research-architecture.md) §1.2, §1.3, §1.14, Risk 4;
[research-stack](../research/research-stack.md) §1.10.
