---
id: research-upstream-proposals
title: konekt — proposals to the upstream toolkits
type: research
status: active
date: 2026-08-25
---

# Research: what konekt found in the toolkits, and what it proposes upstream

konekt is a second implementation reading published contracts. That is the position from which the
findings below are worth anything: none of them is a matter of taste, each was read in the version
konekt pins, and each names what konekt does in the meantime.

The rule this project works under: **nothing is forked.** A gap goes upstream as an issue, konekt
works around it in its own code, and the workaround carries a comment naming the issue so it can be
removed rather than inherited. See [research-architecture](research-architecture.md) D9.

The bodies below are what was filed, verbatim. **Reply** records what actually landed — read in the
source and in the published artefact, not taken from the issue's state, because "closed" and "fixed"
are different claims and only one of them is checkable.

**All five closed as completed on 2026-08-25, and all five landed as code.** Four of them changed
konekt's own plan; the amendments are in [research-architecture](research-architecture.md) §1.3, §1.4,
§1.5 and §1.9, written at the point of divergence rather than by deleting what was there.

| | Repository | Ask | Blocks konekt | Filed | Reply |
|---|---|---|---|---|---|
| [U1](#u1) | kompot | `RemoteThemeDesignSystem` must forward `resolveSurface` to its fallback | no — worked around | [kompot#80](https://github.com/youndie/kompot/issues/80) | closed, `resolveSurface` now delegates to `fallback` — kompot `0.31.0.74` |
| [U2](#u2) | kompot | An unknown component is reported through `println` and reaches no log | no — worked around | [kompot#81](https://github.com/youndie/kompot/issues/81) | closed, `KompotDegradationSink` with three kinds — kompot `0.31.0.74` |
| [U3](#u3) | kompot | `checkbox_input` has no variant, so a switch cannot be drawn as one | no — degraded | [kompot#82](https://github.com/youndie/kompot/issues/82) | closed, `CheckboxInputComponent.variant` + `KompotCheckboxVariants.SWITCH` |
| [U4](#u4) | petich | Dropping events on a non-outbox repository is silent and uncounted | no — guarded locally | [petich#3](https://github.com/youndie/petich/issues/3) | closed, `onDroppedEvents` + `requireOutbox` — petich `0.1.0.6` |
| [U5](#u5) | katcher | The client publishes no Apple target, so an iOS build cannot report | yes — iOS uncovered | [katcher#25](https://github.com/youndie/katcher/issues/25) | closed, all three iOS targets published — katcher `client:0.6.2` |

---

## U1

**Repository:** `youndie/kompot`
**Title:** `RemoteThemeDesignSystem` drops `resolveSurface`, so a server theme silently reverts every surface role

> Found while wiring a white-label eSIM account against the published contract — a second
> implementation, reported from konekt.
>
> ## What happens
>
> `KompotDesignSystem` gained a third hook when #33 was closed:
>
> ```kotlin
> @Composable
> fun resolveSurface(role: SurfaceRole): KompotSurface = KompotSurface()
> ```
>
> `RemoteThemeDesignSystem` overrides `resolveColor` and `resolveTypography`. It does not override
> `resolveSurface`, and it does not delegate it to the `fallback` it already holds. So it answers the
> interface default — "the toolkit's own default for this role" — for every role, discarding whatever
> the wrapped design system would have said.
>
> The class documents itself as *"an overlay, not a replacement: a theme that redefines three tokens
> out of twenty is a valid theme rather than a broken screen"*. For colour and typography that holds.
> For surfaces it inverts: an empty theme replaces everything.
>
> ## Why it is hard to see
>
> `rememberKompotDesignSystem(theme, fallback)` returns `fallback` until the theme arrives, and the
> comment says so — *"the first frame does not wait for the network"*. So the application starts
> **correct**: borderless fields, the brand's button shape, the read-only affordance. The theme lands a
> few hundred milliseconds later, the composition re-reads `LocalKompotDesignSystem`, and the controls
> revert to Material's pill and `OutlinedTextField`'s border. Nothing throws, nothing logs, and the
> recomposition that does it is the one the feature exists to trigger.
>
> A screenshot test that renders with the theme already present never sees it either — both sides of
> the comparison are wrong in the same way.
>
> Three renderers read the hook and are therefore affected: `button`
> (`kompot-client/.../Components.kt:198`), `text_input` and `read_only_field`
> (`kompot-forms-client/.../FormRenderers.kt:47,84`).
>
> Read in `0.30.0`. `grep -rn resolveSurface` over the repository finds no production override at all —
> the only ones are screenshot-test doubles in `kompot-ds-material-compose/src/desktopTest`.
>
> ## What would close it
>
> One delegating override:
>
> ```kotlin
> @Composable
> override fun resolveSurface(role: SurfaceRole): KompotSurface = fallback.resolveSurface(role)
> ```
>
> A regression test in the shape of the bug: a fallback design system that answers a non-default
> `KompotSurface` for `KompotSurfaceRoles.Field`, wrapped in `RemoteThemeDesignSystem` with a theme
> that describes only colours, asserting the field surface survives.
>
> ## What it costs here
>
> konekt inverts the wrapping — its own design system holds `RemoteThemeDesignSystem` rather than being
> held by it — and guards it with a screenshot test that draws the same screen before and after the
> theme arrives and fails on any difference outside colour and typography. That works, and it means our
> composition root reads backwards from every example in the readme.

---

## U2

**Repository:** `youndie/kompot`
**Title:** An unknown component is invisible from both sides: nothing is drawn and nothing is reported

> Reported from konekt, a white-label client built against the published contract.
>
> ## What happens
>
> `UnknownComponentRenderer` with no server-supplied `fallback`:
>
> ```kotlin
> if (fallback == null) {
>     println("[Kompot] Unknown component \"${component.originalType}\" skipped")
>     return
> }
> ```
>
> Two separate things, and only the second is a request:
>
> **Drawing nothing** is a defensible default and a deployment can replace it — the registry is a plain
> `Map<KClass, KompotComponentRenderer>` and `UnknownComponent::class` is an ordinary entry in it. No
> complaint.
>
> **`println`** is the one that cannot be worked around from outside. It is the only signal that a
> client met a type it did not know, and it goes to stdout: on Android it is not a logcat tag anyone
> filters, on iOS it reaches nobody, and in neither case can it be routed to the deployment's own
> logging, breadcrumbs or crash context. The situation this exists for is precisely a newer server
> against an older client in the field, and that is exactly where nobody is holding a console.
>
> The same applies one layer down to `UnknownAction`, which has no reporting at all.
>
> ## Why it matters more than it looks
>
> Graceful degradation converts a crash into a hole. A crash is reported by every crash reporter ever
> written; a hole is reported by nobody. So the feature that makes an old client survive a new server
> is also the feature that makes the survival unobservable — and "how many of our installs are missing
> this component" is the question a staged rollout is decided on.
>
> ## What would close it
>
> A sink on the client, defaulted to today's behaviour so nothing changes for an existing deployment:
>
> ```kotlin
> fun interface KompotDegradationSink {
>     fun onUnknown(kind: KompotDegradationKind, originalType: String, drawnAsFallback: Boolean)
> }
>
> val LocalKompotDegradationSink = staticCompositionLocalOf { KompotDegradationSink { … println … } }
> ```
>
> A malformed theme token is the third case of the same shape and would fit the same sink —
> `parseArgbHex` returning null is currently indistinguishable from a token the theme chose not to
> override.
>
> ## What it costs here
>
> konekt registers its own renderer for `UnknownComponent` anyway, because its design calls for a
> visible placeholder in two densities rather than a gap. So the drawing is solved locally; the
> reporting is solved locally *only for components we draw ourselves*, and `UnknownAction` stays
> unreported.

---

## U3

**Repository:** `youndie/kompot`
**Title:** `checkbox_input` has no variant, so a boolean drawn as a switch needs a component of its own

> Reported from konekt. Smaller than #33's `button` variant ask, and the same shape.
>
> ## What happens
>
> `kompot-forms` offers `checkbox_input` for a boolean field. A settings screen — roaming on, data
> saver on, notifications on — draws those as switches, which on both iOS and Android carry a
> different meaning from a checkbox: a switch takes effect now, a checkbox takes effect on submit.
>
> The wire has no way to say which. `CheckboxInputComponent` has no variant, so a deployment either
> draws every boolean as a checkbox, or replaces the renderer for `checkbox_input` and then decides
> from something outside the component — the field id, in practice, which is a guess that the server
> does not share.
>
> `form-standard`'s `checkbox_field` / `boolean_value` already carry the state correctly; nothing about
> the state is wrong. This is only about the affordance.
>
> ## What would close it
>
> `variant: String? = null` on `CheckboxInputComponent`, resolved through the mechanism that already
> exists for buttons — `KompotSurfaceRoles.button(variant)` composes `"button.quiet"` from a
> server-sent word, and `"checkbox_input.switch"` would compose the same way. Appearance stays a
> client-side resolution of a server-sent name, which is the property #33 was closed while protecting.
>
> ## What it costs here
>
> konekt draws switches through its own component, so its settings screen leaves the toolkit's form
> machinery — validation, visibility rules, the patch protocol — for a control that needs none of it
> and would rather have it.

---

## U4

**Repository:** `youndie/petich`
**Title:** A repository without outbox support drops events silently, and nothing counts it

> Reported from konekt, which wires the petich outbox into a message broker.
>
> ## What happens
>
> From the readme, §What it solves:
>
> > A repository without that support still works; the engine quietly falls back to a plain update and
> > drops the events.
>
> This is documented and it is a reasonable default — an application that does not want events should
> not have to configure their absence. What is missing is any way to find out it happened.
>
> The failure mode is the worst-shaped one there is: **the work happened and nobody was told.** The
> saga completes. Its state is correct. Every assertion anyone naturally writes — `saga.state ==
> COMPLETED`, the balance moved, the row exists — passes. The consumer on the other end of the event
> simply never runs, and the first symptom is a support ticket about a notification that did not
> arrive, months later, in a subsystem nobody suspects.
>
> It is also easy to reach by accident. `petich-postgres` is outbox-aware; a test double, an in-memory
> repository written for a unit test, or a hand-rolled repository is not. So the common shape is a
> suite that is green precisely because it uses the repository that drops events, guarding production
> code that uses the one that does not.
>
> ## What would close it
>
> `PetichEngineMetrics` already exists for questions that cannot be answered from outside, and this is
> one. A `droppedEvents` counter costs one increment on the fallback path, and it is enough: a
> deployment that graphs it sees a flat non-zero line and knows immediately.
>
> Two cheaper halves, if the counter is not wanted: a one-line WARN at engine construction naming the
> repository class and the fact that events are disabled — construction time rather than fallback
> time, because by fallback time the process is in production — or a `requireOutbox` flag on the engine
> configuration that refuses to construct instead.
>
> ## What it costs here
>
> konekt asserts at startup that the configured repository implements the outbox interface and refuses
> to boot otherwise, plus a test that reads the outbox row inside the committed transaction of a saga.
> Both are guards against a specific known trap rather than against a class of mistake, and the next
> project to wire petich to a broker will write them again from scratch — or, more likely, not.

---

## U5

**Repository:** `youndie/katcher`
**Title:** The Kotlin client publishes no Apple target, so a Compose Multiplatform iOS build cannot report a crash

> Reported from konekt: Compose Multiplatform on Android and iOS, with the rest of the stack —
> metrik, tracy, katcher — behind it.
>
> ## What happens
>
> `katcher:client:0.5.1` publishes two variants. From
> `ru/workinprogress/katcher/client/0.5.1/client-0.5.1.module`:
>
> ```
> jvmApiElements-published      | jvm
> nativeApiElements-published   | linux_x64
> ```
>
> `client/build.gradle.kts` declares `jvm()` and one host-dependent native target, chosen from
> `os.name` and `os.arch` at configuration time — macOS, Linux or Windows. No Apple target is
> declared, and since CI publishes from Linux the one native variant that ships is `linux_x64`.
>
> Android is covered by a separate coordinate, `client-android:0.4.92`, with
> `android-gradle-plugin:0.4.92` uploading the R8 mapping. So a Kotlin Multiplatform application has
> katcher on its server, katcher on its Android build, and nothing at all on iOS.
>
> ## Why the host-dependent target is a second problem
>
> Which native variant exists in a published version depends on which machine published it. A release
> cut from a Mac would ship `macos_arm64` and no `linux_x64`, and a consumer pinning a version would
> find their dependency resolving or not depending on which release they landed on. That is worth
> separating from the iOS ask even if the iOS ask is declined.
>
> ## What would close it
>
> `iosArm64()`, `iosSimulatorArm64()` and `iosX64()` on the `client` module. The client is described
> as *"a tiny built-in client… uses the standard Ktor Client"*, and Ktor's client publishes for all
> three, so the reporting path itself should port. Uncaught-exception capture on Kotlin/Native needs
> `setUnhandledExceptionHook`, which is a different mechanism from the JVM's
> `Thread.UncaughtExceptionHandler` — worth saying out loud, since it is the part that does not come
> free with the target declaration.
>
> Declaring the native targets explicitly rather than from `os.name` would fix the second problem on
> its own.
>
> ## What it costs here
>
> konekt v1 collects no crashes from its iOS build, and says so in its service documentation rather
> than adding a different vendor's SDK to a build whose purpose is to exercise this stack. An empty
> answer that names itself is worth more than a full one from somewhere else — but it is still empty,
> and iOS is half the installs.


---

## Second round, filed while building

Found by doing the work rather than by reading, which is the difference between these three and the
first five: each one blocked or corrupted something that was being built at the time.

| | Repository | Ask | Blocks konekt | Filed |
|---|---|---|---|---|
| U6 | petich | the Exposed repositories are in the **default package**, so no packaged Kotlin can reference them | **yes** — worked around reflectively | [petich#8](https://github.com/youndie/petich/issues/8) |
| U7 | petich | two tables ask for an index in a comment and declare none, so the migration generator proposes dropping it | no — filtered in our schema check | [petich#9](https://github.com/youndie/petich/issues/9) |
| U8 | Exposed | `generateMigrations` overwrites its own files, so a table is lost silently | no — the draft is reviewed anyway | [JetBrains/Exposed#2897](https://github.com/JetBrains/Exposed/issues/2897) |

**U6 is the one that mattered.** `ExposedPetichRepository` and its three siblings compile into the
default package — in `petich-postgres-0.1.0.6.jar` the classes sit at the root of the archive with no
directory. Kotlin cannot import from the default package and neither can Java, so no file in a named
package can reference them: not by import, and not by fully qualified name, because there is none to
write. That is the module's entire purpose, unreachable from every application that puts its own code
in a package.

It compiles and publishes green upstream because nothing there notices: `petich-postgres` has no
tests, and a same-module reference from another default-package file resolves fine. The failure exists
only from outside — the shape [proba](https://github.com/youndie/proba) exists for.

konekt constructs the repository by name and casts to `OutboxAwarePetichRepository`, which *is*
packaged. Three lines, no duplicated logic; the alternative was reimplementing the optimistic lock,
the outbox batch insert and the expiry query. What it costs is the compiler's opinion on the
constructor, which is why `PetichStorageTest` builds a real repository rather than a double.

**U8 was reported wrong the first time and rewritten.** It went out saying a table is omitted when
two tables reference the same parent — a symptom published as a mechanism, which would have sent a
maintainer to the diffing. The diffing is correct: `MigrationUtils` returns all three statements. The
plugin names each file from its first statement plus a second-resolution version, so files collide
and overwrite. See [research-stack](research-stack.md) §1.9; the cost of finding this out was one
experiment that should have been run before the issue was filed.

**U7 and U8 compound.** petich asks for two indexes in column comments and declares neither, so
Exposed's view of the schema does not contain them — and Exposed's view is what `generateMigrations`
and `MigrationUtils` compare against. A consumer who follows the comments and then adopts the
standard Exposed workflow is handed a migration that deletes the two indexes the comments asked for,
on the busiest table in the system. It applies cleanly and costs a sequential scan per sweep.

---

## What came back

Filed on 2026-08-25, all five closed the same day, and each verified in the source and in the
published artefact rather than in the issue's state.

**[U1](#u1) → [kompot#80](https://github.com/youndie/kompot/issues/80).** `RemoteThemeDesignSystem`
now carries `override fun resolveSurface(role) = fallback.resolveSurface(role)`. konekt no longer
needs the inverted wrapping of D3 — the composition root can be written the way every example in the
readme writes it. The screenshot test that guarded the workaround is still worth having, and it now
guards the toolkit's behaviour instead of ours.

**[U2](#u2) → [kompot#81](https://github.com/youndie/kompot/issues/81).** Closed wider than it was
asked. `KompotDegradationSink` reports three kinds, not one: `UNKNOWN_COMPONENT`,
`UNKNOWN_ACTION` — which was mentioned in the issue as an aside — and `UNRENDERABLE_COMPONENT`, a
case the issue did not name at all: the type decodes and this build's registry has no renderer for it.
`drawnAsFallback` distinguishes a hole from a placeholder, which is the fact a staged rollout is
decided on. The default is still the `println`, so nothing changes for a deployment that does not set
one. konekt provides a sink into tracy and a katcher breadcrumb, and keeps its own renderer for the
*drawing*, which was never the ask.

**[U3](#u3) → [kompot#82](https://github.com/youndie/kompot/issues/82).**
`CheckboxInputComponent.variant: String?`, an open string like a button's variant, with
`KompotCheckboxVariants.SWITCH` as the one word the standard renderer acts on and anything else
degrading to a checkbox. The constant lives in `kompot-forms` rather than in the client, because the
server is the side that has to spell it. konekt's component dictionary therefore loses `switch_input`
before it was written — nine own components rather than ten.

**[U4](#u4) → [petich#3](https://github.com/youndie/petich/issues/3).** Both halves, and the second
is the one that matters here: `PetichEngineMetrics.onDroppedEvents` fires per event lost, and
`PetichEngineConfig(requireOutbox = true)` refuses to build an engine whose repository cannot store
them. Both off by default, so an application that wants no events is unaffected. konekt sets
`requireOutbox = true`, which replaces the hand-written startup assertion of `B-09` with a
configuration flag — the committed-outbox-row test stays, because it checks a different thing.

**[U5](#u5) → [katcher#25](https://github.com/youndie/katcher/issues/25).** `client:0.6.2` publishes
`ios_arm64`, `ios_simulator_arm64`, `ios_x64`, both macOS, both Linux, `mingw_x64` and `jvm`. The
host-picked native target is gone, which was the second, smaller ask in the same issue. So the iOS
build **can** report a crash, and `B-27` changes from "write the gap down" to "wire it up". The
client and the server also now share one version line, so the three katcher entries in the version
catalogue became two.
