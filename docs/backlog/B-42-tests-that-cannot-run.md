---
id: B-42
title: "A @Test whose return type is not void is silently ignored, and three of them were"
status: done
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

## What landed, and it checks something different from what this item proposed

The check compares the number of `@Test` annotations a class **declares** against the number of cases
JUnit **reported** for it, after every test run, in `konekt.base` — so every module has it and one
added later gets it without anybody remembering.

**The item asked for a bytecode check and that was the wrong instrument**, though its reasoning was
right: no regex over Kotlin can determine a return type. This does not try to. Counting annotations
and comparing against the report needs no return type at all, and catches strictly more — a method
ignored for any reason, and a class not picked up at all. It also drops the dependency the Class-File
API would have added on whichever JVM the Gradle daemon happens to run on, which is not the same JVM
as the toolchain and is not the same on every machine.

Reported may legitimately EXCEED declared: a `@TestFactory` produces dynamic cases and viddik's
generated fixture is one. Only a shortfall is a defect.

- AC MET: a `@Test` that does not run fails the build, naming the class and the shortfall. Proved by
  taking the `: Unit` off `TrafficChainTest`'s resurrected test — the original defect, on the original
  file: `io.konekt.mocks.TrafficChainTest declares 5 @Test and JUnit ran 4`.
- AC MET: the check reports how many classes it read, and refuses a run that wrote no results at all —
  a comparison with nothing to compare passes every time.
- AC DEVIATION: it fails `./gradlew check` rather than `make check`. `make check` is the documentation
  gate here and deliberately needs no JVM; CI runs both, so the gate that catches this is the one that
  already compiles the code.

## Two false positives, found by running it rather than by reading it

Both would have made the check useless in a different way, and neither was visible from the code.

**A multiplatform test task names its suite `MyTest[jvm]`.** Comparing simple names without stripping
the target reported every `commonTest` class in every multiplatform module as never having run. A
guard that cries wolf over a whole module is one that gets deleted in the week it first gets in the
way.

**A filtered task is running a subset on purpose.** `:client:viddikVerify` is a `Test` task narrowed
to the generated screenshot fixtures, and it reported six classes as unrun — correctly, and
meaninglessly, because the unfiltered `jvmTest` beside it covers them. Filtered tasks are skipped and
**say so on the console**: a check that silently declines to check is the same shape of silence it
exists to catch.

## Not covered

**Kotlin/Native test tasks.** `:client:iosSimulatorArm64Test` is a `KotlinNativeTest` and not a
`Test`, so nothing here sees it — and the three iOS cases in that module are exactly the kind of small,
new suite where one silently not running would go unnoticed. Written down rather than left to be
discovered.
