---
id: B-101
title: "Every form is drawn with no patch fetcher, so the custom package's price never changes"
status: done
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

## What was done

`KonektRoutes` gains a `patches` map beside `submits`, built the same way — from the `@Resource` the
server already routes with:

```kotlin
val patches: Map<String, String> =
    mapOf(CustomPackageFields.FORM_ID to addressOf<CustomPackagePatch>())
```

**A map rather than the form's address plus `/patch`.** The convention would have been shorter and it
would have been the second copy of a path that the whole `@Resource` arrangement exists to prevent —
and it would have been a copy nothing checks, since the client would spell what the server declares.

**A form absent from the map is drawn without a fetcher, and that is a state rather than an
omission.** The login form validates locally and asks the server nothing until it is submitted; a
fetcher there would be a round trip nobody wants. The lookup answers null and the screen is right.

## The test stopped repairing the application

The change that matters more than the three lines: `CustomPackageFormStandTest` used to call
`KonektFormScreen` directly and pass `source.patchFetcher(patchAddress, formId)` **itself**. It proved
the fetcher works when somebody supplies one, while the application supplied `null` — so it was green
for eight commits over a screen whose price never moved.

It now renders through `source.render(Screen.Form(response))`, the path the application takes, and
takes the addresses from `KonektRoutes` rather than spelling them. A test that constructs the
collaborator the product forgets is a test of the collaborator.

## Verified

- **Proved by mutation**: `patchFetcher = null` restored, and
  *"choosing a quantity reprices the form without redrawing it"* fails. Before this item that same
  mutation was the production code and the test was green.
- The server half was measured first and needed nothing: 0/1/10/50 GB price at
  `$0` / `$1.50` / `$21` / `$104`.
- `:client:standTest` green against a running stand; the desktop application restarted on the fix.

## What was NOT done

**No guard that a form is never rendered without a fetcher.** The honest shape would be a check that
every served form id appears in `patches` — but that is false by design for login, so the guard would
need an exemption list, and an exemption in a completeness guard is how the gap comes back. The stand
test going through `render` is what stands in its place, and it is weaker: it covers the one form that
patches. Said here rather than left as an impression that something watches this.

## Anchors

| What | Where |
|---|---|
| The map | `client/src/commonMain/kotlin/io/konekt/client/app/KonektRoutes.kt` (`patches`) |
| Where it is used | `client/src/commonMain/kotlin/io/konekt/client/app/KonektScreenSource.kt` (`render`) |
| The test that goes through the application's path now | `client/src/jvmTest/kotlin/io/konekt/client/stand/CustomPackageFormStandTest.kt` |
