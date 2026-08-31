---
id: B-101
title: "Every form is drawn with no patch fetcher, so the custom package's price never changes"
status: open
priority: P1
size: S
stage: stage-m7-completeness
---

# B-101 — The price is computed correctly and never reaches the screen

`KonektScreenSource.render` draws every form like this:

```kotlin
is Screen.Form -> KonektFormScreen(
    response = screen.response,
    patchFetcher = null,        // ← here
```

A `FormController` built with no fetcher cannot ask the server to recompute anything, so the custom
package builder's price sits at `$0` whatever the subscriber chooses. Found by using the application.

**The server is not at fault**, measured against the running stand:

| data GB | minutes | messages | price the patch answers |
|---|---|---|---|
| 0 | 0 | 0 | `$0` |
| 1 | 0 | 0 | `$1.50` |
| 10 | 300 | 0 | `$21` |
| 50 | 1200 | 500 | `$104` |

**And the implementation exists.** `KonektScreenSource.patchFetcher(address, formId)` is written,
commented, correct — and called from nowhere in the application. This is the shape `B-56` named:
code that is written and never wired reads as done in review and is absent at runtime.

## Why no test caught it

Both tests that could have are blind to it, in different ways, and the second one is the interesting
one:

* `:e2e CustomPackageScenarioTest` drives the endpoint over HTTP. It proves the server recomputes,
  which is exactly the half that works;
* `client CustomPackageFormStandTest` renders the real `KonektFormScreen` against a real stand — and
  **passes `source.patchFetcher(patchAddress, response.schema.formId)` itself**. It supplies the very
  thing the application leaves null, so it proves the wiring works when it is done and says nothing
  about whether anything does it.

A test that builds the collaborator the product forgets is a test of the collaborator.

## The decision

- **The fetcher comes from the screen's own address.** `Screen.Form` already knows where it was
  fetched from; the patch address is that plus `/patch`, which is what the server routes
  (`CustomPackagePatch`) and what `submits[formId]` already does for the submit side.
- **The stand test stops supplying it.** It must render the form the way the application renders it,
  and get the fetcher from the same place — otherwise it goes on passing over the next omission.
- **Not a new endpoint and not a new component.** Everything needed is served and implemented; this
  is one argument.

## Acceptance criteria

- AC: choosing a quantity in the builder changes the displayed price, in the running desktop
  application, verified by a person or a screenshot rather than by a unit test alone.
- AC: `CustomPackageFormStandTest` builds its screen through the same path the application uses, so
  that removing the wiring again turns it red. Proved by mutation — restore `null` and the test fails.
- AC: a guard that a form screen is never rendered with a null fetcher, if it can be written without
  ceremony; if it cannot, say so rather than leaving the impression it exists.
- AC: the other served form — login — still works, because it has no patch and must not acquire one.

## Anchors

| What | Where |
|---|---|
| The null | `client/src/commonMain/kotlin/io/konekt/client/app/KonektScreenSource.kt` (`render`, `Screen.Form`) |
| The implementation nobody calls | the same file, `patchFetcher(address, formId)` |
| The test that supplies it for the app | `client/src/jvmTest/kotlin/io/konekt/client/stand/CustomPackageFormStandTest.kt` |
| The server half, which works | `server/src/main/kotlin/io/konekt/packages/CustomPackageRouting.kt` |
