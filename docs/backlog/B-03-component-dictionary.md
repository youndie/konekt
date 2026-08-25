---
id: B-03
title: "Fix the component dictionary: nine own wire types in one KSP module"
status: open
priority: P0
size: L
stage: stage-m0-wire
blocked_by: [B-01]
---

# B-03 — Fix the component dictionary: nine own wire types in one KSP module

In a backend-driven product the component dictionary **is** the API. Research §1.5 established what
the toolkit already has — `column`, `row`, `text`, `button`, `table`, `paginated_list` and the eight
form inputs — and the canvas names nine things it does not: the usage counter card, the plan card,
the QR block, the eSIM card, the order row, the banner, the snackbar, the step meter and the
skeleton. Renaming any of them after the first screen is a coordinated release of both sides.

- **The decision and its reason.** One module, one `kompotModuleTag`, all nine types declared before
  any screen is built. The tag must be unique because generated registrations land in one package,
  and the generated code compiles into this module's own artefact, so the client never applies KSP.
- The rejected alternative is growing the dictionary screen by screen. It produces a type per screen
  rather than a type per concept, and the second screen that needs a counter invents a second counter.
- Not covered: the renderers. This item is the wire side and its schema; drawing is B-05 and the
  screen items.

- AC: `KompotSpec.generateAll(KompotToolkitSpec.modules + konektSpecModule())` produces a profile
  containing all nine types, committed under `shared/spec/`.
- AC: each type is listed in [design-app-canvas](../design/design-app-canvas.md) with the same name.
- Anchors: `shared/components/src/commonMain/kotlin/io/konekt/components/`,
  `shared/components/build.gradle.kts`.

Background: [research-architecture](../research/research-architecture.md) §1.5,
[design-app-canvas](../design/design-app-canvas.md).
