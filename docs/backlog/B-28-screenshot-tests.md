---
id: B-28
title: "Screenshot tests for the counter states and both brands"
status: done
priority: P2
size: M
stage: stage-m4-proof
blocked_by: [B-22, B-43]
---

# B-28 — Screenshot tests for the counter states and both brands

The canvas draws every state as its own frame precisely so they can be compared. viddik
(`0.1.2.13`, a Gradle plugin) generates the cases; the subjects are the counter card in normal, low
and exhausted, the four purchase states, and one screen in each brand.

- **The decision and its reason.** The brand pair is the important one: it is the assertion that
  nothing in the layout depends on the shape scale, which is what makes the client-side shape constant
  of D2 safe. The counter states are the second, because their difference is copy rather than colour
  and copy is what regresses silently.
- The rejected alternative is manual review against the canvas. It works once, at the moment somebody
  looks.
- Not covered: goldens for the placeholder of B-25, which needs the dev route and belongs with it.
- Generated cases need a runner that matches the framework they were generated for; a suite that
  compiles and runs zero tests is the failure mode here, so the gate asserts the case count.

- AC: `./gradlew :client:viddikVerify` compares the named subjects and reports a non-zero case count.
- AC: changing brand A's `lg` radius fails only the brand A goldens. **Unsatisfiable when this was
  written and satisfied since `B-112`**: `lg` was read by `buttonShape` alone and only when pills
  were off, so brand A stated a radius nothing drew. The canvas pairs its headline blocks with
  `lg`, `CardGeometry.Tier.CARD` now resolves to it, and the mutation moves sixteen goldens with
  none of brand B's among them. The trap below is unchanged and still worth reading. **This AC
  has a trap `B-22` measured**: `RoundedCornerShape` clamps a corner to half the smaller dimension, so on a button at
  Material's default 40dp height every radius of 20dp or more draws the identical pill — brand A's
  `lg` is 36 and brand B's is 22, and both render as the same pill. Changing brand A's `lg` from 36 to
  anything else above 20 therefore fails NOTHING on a default-height button, and a golden pair that
  contains only such buttons would satisfy this AC by drawing nothing brand-specific at all. The
  subject needs a control taller than 44dp (48 is the canvas's minimum touch target and the first
  ordinary size that discriminates) or a `text_input`, whose `sm` radius — 12 against 8 — is far below
  the clamp. Numbers and the sweep are in [design-brand-kit](../design/design-brand-kit.md).
- Also, carried from `B-04`: **the golden pair for the design system itself.** B-04's guard compares
  two frames within one run, which catches a theme that moves geometry and cannot catch konekt's own
  surfaces drifting — both frames move together. A committed golden of the brand-A controls is what
  notices that, and it needs the screenshot harness this item brings.

- Note on the fixture: `client/src/jvmTest/kotlin/io/konekt/client/theme/BrandSwitchTest.kt` is the
  in-run comparison this item's goldens complement, and its `controls` column is a ready-made subject
  — no background (the alpha channel is the silhouette) and a `read_only_field`, whose fill and border
  konekt takes away and the toolkit's default puts back.
- Note on a dependency, measured while writing B-22: rendering a `text_input` under
  `runComposeUiTest` on the desktop JVM throws `IllegalStateException: Dispatchers.Main was accessed
  when the platform dispatcher was absent`. A button, a `text` and a `read_only_field` do not. Any
  screenshot subject containing a form field needs `Dispatchers.setMain` or a main-dispatcher
  artefact, and the failure names the dispatcher rather than the field.

- Anchors: `client/build.gradle.kts`, `client/src/jvmTest/kotlin/io/konekt/screenshots/`.

Background: [design-app-canvas](../design/design-app-canvas.md).

## What was built

Eight goldens in `client/src/jvmTest/snapshots/`, recorded from the application's own composition
root — `KonektTheme` resolving a kit the server actually ships and `konektRegistry()` choosing the
renderers — rather than from a design system assembled in the fixture:

| Case | Golden | What it is for |
|---|---|---|
| `Counter - Normal` | `Counter_Normal.png` | the ordinary card, no caption |
| `Counter - Low` | `Counter_Low.png` | the canvas's projection copy, `secondary` accent |
| `Counter - Exhausted` | `Counter_Exhausted.png` | `error` accent |
| `Counter - Unknown state` | `Counter_Unknown_state.png` | a word on the wire this build does not know |
| `Brand - A` / `Brand - A Dark` | `Brand_A*.png` | section 08's pair, light and dark |
| `Brand - B` / `Brand - B Dark` | `Brand_B*.png` | the same markup, other kit, other radii |

Recording is `LOCAL=1 ./gradlew :client:viddikRecord` — on the Mac, because the Linux box is a one-way
replica and reverts what a task writes there. Verification runs anywhere: the goldens recorded on
macOS/arm64 were verified byte-clean on Linux/amd64, which works because the fixtures pin viddik's
bundled font (`viddikTypography()`) instead of the host's. `viddik { verifyOnCheck = true }`, so
`:client:check` runs the comparison.

## AC 1 — satisfied, and the empty-run trap turned out to be closed upstream

`:client:viddikVerify` prints `viddikVerify compared 8 screenshot case(s)` and `:client:check` runs
it. The count is read back out of the task's own JUnit XML by a `doLast` in `client/build.gradle.kts`.

**That `doLast` is a report and not a guard, and it says so**, because three attempts to make it fire
all failed for the same good reason — viddik 0.1.2.13 closes the empty run before it:

| Mutation | What happened |
|---|---|
| `useJUnit()` on `viddikVerify` (the recorded JUnit-4 trap) | did not take: `ViddikPlugin` calls `useJUnitPlatform()` on its own task. Still compared 8 cases |
| `filter { excludeTestsMatching("*GeneratedViddikTests*") }` | Gradle failed the task: *No tests found for given includes* — the plugin's own include has `failOnNoMatchingTests` on |
| `viddik { generateTests = false }` | the plugin failed the task by name, saying there is nothing to run |

The guarding is therefore done by `ScreenshotCasesTest`, which is stronger anyway: it names all eight
cases, checks the goldens on disk in BOTH directions, and — because it reads the GENERATED
`GeneratedViddikRegistry` — fails to compile at all if KSP produced nothing. Proved to bite by
renaming `Counter_Low.png` to `Counter_Lowe.png`: it failed naming both the missing file and the
orphan, and `GoldenContentTest` failed beside it.

That mutation also found a hole worth keeping: **the goldens are not an input of `jvmTest` by
default.** The first run of it reported `BUILD SUCCESSFUL` and UP-TO-DATE while a golden was missing,
because `src/jvmTest/snapshots` belongs to no source set. `client/build.gradle.kts` now declares it,
the way viddik declares it for its own task.

## AC 2 — the AC cannot hold as written, and the reason is one level below B-22's trap

**Changing brand A's `lg` fails nothing at all — not merely on a short button, but anywhere.**
Measured: `BrandA.large` set from 36 to 8, `:client:viddikVerify` green on all eight cases.

`largeShape` is read by exactly one thing, `KonektShapeScale.buttonShape`, and only on the
`!pillButtons` branch. Brand A has `pillButtons = true`, and no other surface role reads it —
`Container` takes `md`, `Field` and `ReadOnlyField` take `sm`. So brand A's 36 is a number the canvas
states and this build never draws. B-22 found the clamp; the pill is underneath it.

The property the AC stands for **is** satisfied, through the part of the scale brand A actually draws:

| Mutation | Goldens that failed |
|---|---|
| `BrandA.medium` 20 → 8 | `Counter - Normal`, `Counter - Low`, `Counter - Exhausted`, `Counter - Unknown state`, `Brand - A`, `Brand - A Dark` — the six frames drawn in brand A |
| `BrandA.large` 36 → 8 | none |
| `BrandB.large` 22 → 8 | `Brand - B`, `Brand - B Dark` only |

The counter frames are drawn in brand A, so "only the brand A goldens" means all six brand-A frames
and neither brand-B frame — which is what was observed. Whether brand A ought to have a role that
reads `lg` is a design decision, not a test fix, and it is written up in
[design-brand-kit](../design/design-brand-kit.md).

## The defect the goldens found on their first run

The first recording came out with **brand A's LIGHT frame drawn half in the dark palette**: the
counter card in `#18211F` (dark `surface_variant`) under a button in `#0B6B60` (light `primary`), and
the title in the dark palette's `on_background` on a near-white ground.

`KonektTheme` built its Material scheme from the `darkMode` it was given and resolved every
`ColorToken` through the toolkit's `rememberKompotDesignSystem`, which constructs a
`RemoteThemeDesignSystem` with `darkModeOverride = null` — and that class then asks
`isSystemInDarkTheme()`, the **host machine's** appearance setting. So the frame was half of each, and
which half depended on the machine: reverting the fix and verifying on the Linux box failed
`Brand - A Dark` and `Brand - B Dark` instead, the mirror image.

Fixed by constructing `RemoteThemeDesignSystem` directly with `darkModeOverride = darkMode`.
`BrandSwitchTest`'s manual path was changed the same way, or its last test would compare two different
clients. `GoldenContentTest` now asserts the property host-independently: a light frame may contain no
colour that exists only in the dark half of the kit, and the reverse. Proved to bite by copying
`Brand_A_Dark.png` over `Brand_A.png` — it failed naming the five trespassing colours.

## What the goldens are asserted to CONTAIN

A headless capture succeeds on broken content, so `GoldenContentTest` reads inside every file:

- **the accent role per counter state, looked up in the kit the server ships** — `Counter_Normal.png`
  contains brand A's `primary` and neither `secondary` nor `error`, and so on for the other two. It is
  the served palette that decides, not the test;
- **`Counter - Unknown state` is pixel-identical to `Counter - Normal`.** The two fixtures carry the
  same data and differ in one word on the wire; the identity is the degradation rule, drawn. Proved to
  bite by giving the `else` branch of `accentToken()` its own colour — `Counter - Unknown state` was
  then the only failing case;
- **the brand pair differs in geometry**, not only in colour: 796 pixels change ALPHA between
  `Brand_A.png` and `Brand_B.png` (and 71371 change colour). Alpha is the silhouette;
- **dark mode moves nothing**: 0 pixels change alpha between `Brand_A.png` and `Brand_A_Dark.png`;
- every golden is at least 10% opaque and carries more than 64 distinct colours. The committed set
  runs 51–62% opaque and 348–762 colours.

**The split of responsibilities was stated wrong here and is corrected.** The first half holds:
breaking the renderer fails `viddikVerify` and leaves `jvmTest` green. The second half — "substituting
a golden fails `jvmTest` and leaves `viddikVerify` green" — is false, and it was written as measured.
Substituting `Counter_Normal.png` for `Counter_Low.png` fails `viddikVerify` too:

```
GeneratedViddikTests > runAllScreenshots() > Counter - Low FAILED
    IllegalStateException: Screenshot mismatch for Counter/Low: 18375/72000 px differ (25.52%, …)
```

which is inevitable from how `ViddikEngine.verify` works — it compares the golden against a fresh
capture, so a golden that is not what the code draws fails whichever way it got that way. The real
division is narrower and still worth having: `viddikVerify` says "the golden and the code disagree"
and cannot say which is wrong, while `jvmTest`'s `ScreenshotCasesTest` and `GoldenContentTest` hold
properties of the golden set on their own — every case has a file, every file has a case, nothing is
blank or single-coloured. That is what catches a bad recording that was recorded from bad code, which
`viddikVerify` is structurally unable to see.

## Deviations from this item as written

- **The fixtures are in `jvmTest`, not `commonTest`.** The anchor said
  `client/src/commonTest/kotlin/io/konekt/screenshots/`; viddik's dependencies and its KSP
  configuration (`kspJvmTest`) live on the jvm test source set, and `commonTest` cannot see the
  annotation. The path is now `client/src/jvmTest/kotlin/io/konekt/screenshots/`.
- **The `text_input` dispatcher note is obsolete for a screenshot.** viddik's `captureComposable`
  calls `Dispatchers.setMain(UnconfinedTestDispatcher())` around the capture itself, so a form field
  in a viddik fixture does not hit `IllegalStateException: Dispatchers.Main was accessed …`. The note
  still holds for a bare `runComposeUiTest`, which is where B-22 met it. No fixture here contains a
  form field, so this was not exercised.
- **`read_only_field` is not in the brand fixture.** B-22 measured that konekt draws it with a
  transparent container and a transparent outline, so its shape paints nothing — 0 pixels differ
  between a 0dp scale and a 40dp one. The counter card carries the `md` radius instead, which is the
  control that discriminates.

## Not done, and why

- **Sections 02 and 03 of the canvas — the four plan states and the four purchase states — are not
  photographed.** They are drawn from `plan_card`, `order_row`, `banner` and `step_meter`, and this
  client registers renderers for two of the nine types (`KonektRendererCoverageTest` holds the lists
  apart). A golden of a frame made entirely of unknown-component blocks would photograph the
  degradation and file it as the purchase flow. They join when their renderers do.
- **Nothing was changed in `.github/`, and nothing needed to be.** `viddik { verifyOnCheck = true }`
  puts `viddikVerify` inside `:client:check`, and the build job already runs `./gradlew build`, so the
  comparison runs in CI without a job of its own. That is worth re-checking if the goldens ever move
  behind a task `build` does not reach.
