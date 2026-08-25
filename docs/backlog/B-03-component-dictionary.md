---
id: B-03
title: "Fix the component dictionary: nine own wire types in one KSP module"
status: done
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

- **The decision and its reason.** One module, one `kompotModuleTag` (`Konekt`), all nine types
  declared before any screen is built. The tag must be unique because generated registrations land in
  one package, and the generated code compiles into this module's own artefact, so the client never
  applies KSP.
- **Nine, not ten.** The tenth was a switch, and kompot#82 closed while this item was open: a settings
  toggle is `checkbox_input` with `variant = KompotCheckboxVariants.SWITCH`, a toolkit component.
- **Every field carries pre-formatted text**, not a number with a unit — `"15,8 GB left"`, `"1 190 ₽"`.
  The server builds the screen, so the server formats. The exceptions are geometry rather than
  language: a counter's `progress` fraction and a step meter's two integers, neither drawable from a
  sentence.
- **Every enum-shaped field is an open string with a constants object**, never a Kotlin enum. An enum
  closes the set at the client's build date and an unknown value fails the decode, taking the screen
  with it; an open string degrades to the neutral form. The constants exist because the server is the
  side that has to spell the word.
- The rejected alternative is growing the dictionary screen by screen. It produces a type per screen
  rather than a type per concept, and the second screen that needs a counter invents a second counter.
- Not covered: the renderers. This item is the wire side and its schema; drawing is B-05 and the
  screen items.

- AC ✅: `KompotSpec.generateAll(KompotToolkitSpec.modules + konektSpecModule())` produces a profile
  naming all nine types, and `shared/spec/schema/konekt-components.schema.json` is committed. Only
  konekt's own file is tracked — the toolkit's thirteen are byte-identical to what kompot commits and
  would be a second source of truth churning on every bump. The trade-off, and the command that
  produces the whole set, are in `shared/spec/schema/README.md`.
- AC ✅: each type is listed in [design-app-canvas](../design/design-app-canvas.md) under the same
  name.
- AC ✅, and this is the one that mattered: `KonektRegistrationTest` round-trips **each** component
  through `generatedKonektSerializersModule` and asserts it is not an `UnknownComponent`. This
  module's build switches off every per-target KSP task so generation happens once against the common
  metadata, and a disabled KSP task is the classic way to get a green and empty build — so the exit
  code of `build` proves nothing here and the per-component assertions are what does. Six tests on
  the JVM and the same six on `iosSimulatorArm64`, none skipped.
- AC ✅: `konektWireNames` lives in `commonMain` and both the dictionary test and the JVM-only spec
  test walk it, so the two lists cannot drift.
- Anchors: `shared/components/src/commonMain/kotlin/io/konekt/components/`,
  `shared/components/build.gradle.kts`, `shared/spec/src/main/kotlin/io/konekt/spec/KonektSpec.kt`,
  `shared/spec/schema/konekt-components.schema.json`.

Background: [research-architecture](../research/research-architecture.md) §1.5,
[research-stack](../research/research-stack.md) §1.8 (three build facts this item paid for),
[design-app-canvas](../design/design-app-canvas.md).
