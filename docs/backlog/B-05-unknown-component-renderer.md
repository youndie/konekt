---
id: B-05
title: "An unknown component draws a block and reports itself"
status: done
priority: P1
size: S
stage: stage-m0-wire
blocked_by: [B-03]
---

# B-05 — An unknown component draws a block and reports itself

The toolkit's `UnknownComponentRenderer` calls `println` and returns when the server named no
fallback — nothing is drawn (research §1.4). The canvas is explicit that this must never be a hole:
a full card when the block is the screen's subject, one line among known rows, *"never a blank gap"*.

- **The decision and its reason.** Replace the registry entry for `UnknownComponent::class`. The
  registry is a plain map and this is a supported extension point, so it costs one entry and no fork.
- **The reporting half is now the toolkit's**, since kompot#81 landed in `0.31.0.74`: konekt provides
  a `KompotDegradationSink` into tracy (with `originalType` as an indexed field) and a katcher
  breadcrumb, rather than writing reporting into its own renderer. That buys two kinds konekt had not
  thought to ask for — `UNRENDERABLE_COMPONENT`, a type that decodes with no renderer registered, and
  `UNKNOWN_ACTION`, a tap that reaches the application and can do nothing.
- The rejected alternative is leaving the default and relying on the server always sending a
  `fallback`. It works until the day it is forgotten, and the symptom is a hole.
- Not covered: nothing, now. `UnknownAction` was the gap here and the sink covers it.

- AC OK: a screen containing a type absent from the registry draws the placeholder in both densities,
  and everything around it still renders — asserted from a WIRE PAYLOAD rather than a constructed
  `UnknownComponent`, because the decode is half the mechanism and a test that skips it proves the
  drawing and not the degradation.
- AC OK, **and reported once**: the render produces exactly one degradation record carrying
  `esim_transfer_widget`, through the toolkit's own sink rather than a channel of ours — a deployment
  sets one sink and hears about all three kinds. A fallback the server named is drawn instead of our
  apology, and the report then says `drawnAsFallback = true`.
- AC PENDING, **the transport half**: the sink is not connected to tracy or to a katcher breadcrumb,
  because neither is wired into this build yet — that is `B-26`. What exists here is the mechanism and
  the once-only property; what `B-26` adds is where the record goes. The unknown-*action* half needs
  no work: the toolkit's `ReportingActionHandler` already dedupes it, which is the finding that made
  kompot#81 worth filing.

**Which density is not this renderer's decision.** A card in the middle of a list is worse than a line
where a card was, and only whoever draws the tree knows which. `LocalUnknownBlockDensity` carries it,
defaulting to the line — the conservative half, because a block among known rows must not push them
off the screen, and a screen whose subject is unknown is rarer than a row that is.

**The wire name is not put in front of the subscriber.** It goes to the sink, where an operator can
count it; on the screen it is a word nobody can act on, which is why the canvas's copy says what to do
instead of what is missing. There is a test for that, because it is the kind of detail a later edit
"improves" by adding the type name.
- Anchors: `client/src/commonMain/kotlin/io/konekt/client/render/UnknownBlockRenderer.kt`,
  `client/src/jvmTest/kotlin/io/konekt/client/render/UnknownBlockRendererTest.kt`.

Background: [research-architecture](../research/research-architecture.md) §1.4.
