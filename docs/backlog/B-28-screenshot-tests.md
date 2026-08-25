---
id: B-28
title: "Screenshot tests for the counter states and both brands"
status: open
priority: P2
size: M
stage: stage-m4-proof
blocked_by: [B-22]
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
- AC: changing brand A's `lg` radius fails only the brand A goldens.
- Also, carried from `B-04`: **the golden pair for the design system itself.** B-04's guard compares
  two frames within one run, which catches a theme that moves geometry and cannot catch konekt's own
  surfaces drifting — both frames move together. A committed golden of the brand-A controls is what
  notices that, and it needs the screenshot harness this item brings.

- Anchors: `client/build.gradle.kts`, `client/src/commonTest/kotlin/io/konekt/screenshots/`.

Background: [design-app-canvas](../design/design-app-canvas.md).
