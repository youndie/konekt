---
id: B-42
title: "A @Test whose return type is not void is silently ignored, and three of them were"
status: open
priority: P1
size: S
stage: stage-m4-proof
---

# B-42 — A @Test whose return type is not void is silently ignored, and three of them were

JUnit 5 does not run a `@Test` method that returns something. It does not warn either — the method is
simply not a test, and the class reports one fewer.

Kotlin makes that easy to write by accident. An expression-bodied test —
`@Test fun \`x\` () = runBlocking { … }` — takes its return type from the last expression, and
`kotlin.test.assertNotNull` returns the value it checked. So a test that ends in `assertNotNull(…)`
compiles, is annotated, is counted by nobody, and never runs.

**Measured rather than supposed.** `javap` over every compiled test class in this build found three,
and two of them predated the item that noticed:

| Test | Ends in | Consequence |
|---|---|---|
| `TrafficChainTest > the copy changes with the state and not only the colour` | `assertNotNull(card.captionText)` | never ran; **it fails when it does** — it was the only thing covering the clamp defect below |
| `HistoryPagingTest > an exactly full page still knows whether there is more` | `assertNotNull(...next)` | never ran; passes once made runnable |
| `TopUpSagaTest > taking a credit back debits exactly what was credited` | `assertNotNull(balances.balanceOf(...))` | written in `B-40`, never ran until `javap` said so |

The first one is the whole argument. `ExposedUsageCounters.consume` applied its subtract and its clamp
as two statements in the wrong order, so **every consumption taking more than half of what was left
zeroed the remainder** — silently, with no negative number and no error. The test that covered it had
been written months earlier and had never executed once.

- **The decision and its reason.** A check that reads the compiled classes, not the sources: the
  question is what the bytecode says the return type is, and no regex over Kotlin can answer that. Java
  25's Class-File API is on the toolchain already, so parsing a method descriptor for a trailing `)V`
  and its `RuntimeVisibleAnnotations` needs no dependency.
- **It has to be a Gradle task rather than a test.** A test in `:server` can only see its own module's
  classes, and making `:server:test` depend on every other module's `testClasses` is a list somebody
  forgets. A task that depends on `testClasses` of every subproject and runs inside `check` has no
  ordering problem and no list.
- The rejected alternative is requiring `: Unit` on every expression-bodied `@Test`. It makes the
  defect unrepresentable — an explicit `Unit` coerces the body — and it is a rule nothing enforces, so
  it decays into the same silence. Worth adopting as a habit **beside** the check, not instead of it.
- Not covered: a test that runs and asserts nothing. That is mutation testing's question, not this one.

- AC: a `@Test` method compiled to a non-void return type fails `make check`, naming the class and the
  method.
- AC: proved by mutation — take the `: Unit` off one of the three above, watch the check name it,
  put it back.
- AC: the check reports how many test classes it read, so a run that found none fails rather than
  passes. A completeness check with nothing to check is the failure this repository keeps meeting.
- Anchors: `build-logic/`, `server/src/test/kotlin/io/konekt/ci/`.

Background: found while building [B-40](B-40-no-way-to-add-money.md).
