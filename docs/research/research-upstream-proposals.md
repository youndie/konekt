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

| | Repository | Ask | Filed | Reply |
|---|---|---|---|---|
| U6 | petich | the Exposed repositories are in the **default package**, so no packaged Kotlin can reference them | [petich#8](https://github.com/youndie/petich/issues/8) | closed, packaged in `0.1.0.8`; our reflective bridge deleted |
| U7 | petich | two tables ask for an index in a comment and declare none, so the migration generator proposes dropping it | [petich#9](https://github.com/youndie/petich/issues/9) | closed, all three declared in `0.1.0.8` under the same names; our `DROP INDEX` exemption deleted |
| U8 | Exposed | `generateMigrations` overwrites its own files, so a table is lost silently | [JetBrains/Exposed#2897](https://github.com/JetBrains/Exposed/issues/2897) | open; fix proposed in [#2898](https://github.com/JetBrains/Exposed/pull/2898) and verified here |
| U9 | kompot | the Compose half publishes no iOS target, so a Compose client stops at Android and desktop | [kompot#84](https://github.com/youndie/kompot/issues/84) | closed, released in `0.31.0.76`; `:client` builds for iOS |
| U10 | kompot | `kompot-tck` assumes the login endpoint is a form, and offers no way to hand it a token | [kompot#85](https://github.com/youndie/kompot/issues/85) | closed, released in `0.32.0.77`; our transport decorator deleted |
| U11 | tracy | the agent publishes no Apple target, so an iOS client cannot log through tracy | [tracy#16](https://github.com/youndie/tracy/issues/16) | closed, released in `0.1.13`; `agent` declares `ios_arm64`, `ios_simulator_arm64` and `ios_x64` in its module metadata and `:client` compiles for both of ours |
| U12 | kompot | a form patch cannot reach a non-editable field, so a server-computed value is editable or stale | [kompot#89](https://github.com/youndie/kompot/issues/89) | closed, released in `0.33.0.86` as an optional `fieldId` on `read_only_field`; B-20's first acceptance criterion met and the form's refetch deleted |
| U13 | kompot | `kompot-tck` knows four endpoint kinds and a form patch is none of them, so nothing checks that a patch names declared fields | [kompot#93](https://github.com/youndie/kompot/issues/93) | closed, released in `0.33.1.91` as a fifth kind `patch`, a `TckConfig.patchEndpoints` pairing and the check that reads it; our unit-test stand-in is now a protocol check, proved by mutation |
| U14 | kompot | a `Background` modifier paints a rectangle, so a server cannot compose a card | [kompot#95](https://github.com/youndie/kompot/issues/95) | open; konekt carries a `surface` component whose only job is the corner |
| U15 | kompot | `kompot-tck` follows a graph route's endpoint literally, so a parameterised destination cannot be in a `NavigationGraph` | not filed yet | konekt keeps that one deeplink in the client instead |
| U16 | kompot | `amount_input` can only put the currency symbol AFTER the number, and two of five currencies put it first | [kompot#97](https://github.com/youndie/kompot/issues/97) | closed, released in `0.33.1.93` as a `currencyPrefix` beside the suffix; our label workaround deleted |
| U17 | kompot | `amount_input` always spaces the symbol away from the number, and `$50` has no space | [kompot#99](https://github.com/youndie/kompot/issues/99) | open; konekt draws `$ 50` in the field beside `$10` in the text under it, and has no workaround — the gap is the field's, not the tree's |

**U10 is what a second implementation is for, in miniature.** `TckRunner.authenticate` posts a fixed
`{formId, fieldId, values}` envelope to `TckConfig.loginPath`, which assumes the way into the server is
an ordinary kompot form. The toolkit does not require that anywhere — `kompot-auth` is one
`update_session` action and everything around it is the application's, which is §1.5 of
[research-architecture](research-architecture.md) — so konekt's OTP exchange takes a plain DTO and the
walk cannot log in.

The cost is not one check. Without a token every secured endpoint answers 401, and `schema`,
`component-id`, `perform`, `text-spans` and `pagination` all report findings about a server that has
none of those defects: six findings across four endpoints, of which exactly one was true. A
conformance kit that cannot authenticate does not fail loudly — it produces a page of confident,
wrong ones.

It was worked around locally in a `TckTransport` decorator that unwrapped the envelope on the one
login path — a rule about a request BODY living in the layer documented as the only thing the checks
know about transport. **That decorator is deleted.** `0.32.0.77` added both of the asks:
`TckConfig.loginBody`, posted verbatim, and `TckConfig.bearerToken`, which skips the exchange.

konekt takes `loginBody`, and the difference is a check rather than a preference: handing the kit a
session skips the login entirely, so nothing then verifies that the exchange answers an
`update_session` carrying an `accessToken` — the part SPEC §12 makes a rule. With `loginBody` the kit
still performs the login and still holds it to that.

The care asked for came back in the code as well: `bearerToken` does not become a header the transport
always adds, because `securedEndpointsRejectAnonymous` asks a secured endpoint for a 401 with no token
at all, and a token applied unconditionally would turn that check green while proving the opposite.

**U8's fix was verified rather than read.** [#2898](https://github.com/JetBrains/Exposed/pull/2898)
appends an index to the version of every migration after the first, and the contributor asked for a
run against this repository's reproduction. Built into `mavenLocal` and measured: all four shapes come
out complete, and Flyway 13.3.0 applies every one against Postgres 18 — where the reported shape used
to yield one file and lose a table, it yields three and loses none.

The same run found one configuration where the fix does not hold: with `fileSeparator = "_"` the index
is written with the separator's own character, Flyway reads the version up to the first separator, and
`Found more than one migration with version …` comes back. Reported in the issue as a measurement.
**Not reviewed as a patch** — the code is not ours, and the line between "you asked us to run it, here
is what we measured" and "here is how to write it" is where an upstream report stops being welcome.
The recipe is [`probes/exposed-2897/verify-a-patch.sh`](../../probes/exposed-2897/verify-a-patch.sh),
committed because the next fix will want it too.

**U9 has no workaround and that is the finding.** The renderers are the toolkit's, so there is
nothing to work around locally: `:client` is a JVM-only module until it closes, and the brief's
"Compose Multiplatform on Android and iOS" is half-available. It was found by pointing a module with
`iosArm64()` at `kompot-client` — which is the only way to find it, because the wire half publishes
iOS and the failure therefore looks like a coordinate problem rather than a missing platform.

**Both petich asks landed in `0.1.0.8` and both workarounds are gone**, verified in the published jar
rather than in the issue state: `ExposedPetichRepository.class` now sits under
`ru/workinprogress/petich/postgres/`, and the three indexes are declared — including the
`outbox_events` one, which the issue mentioned only in passing as having the same shape without the
comment.

Removing the second workaround mattered more than adding it did. `KonektSchemaTest` had been ignoring
`DROP INDEX` in its completeness comparison, which is an exemption written for one case and blind to
the next thing that looks like it: while it stood, an index that genuinely should have gone would
have been ignored too. The assertion is strict again.

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

**U8 is moving upstream.** A contributor offered to take it on 2026-08-25, proposing regression tests
for the filename collision and the duplicate versions plus a naming change that guarantees uniqueness
within one run. That direction closes the data loss, and the reply on the issue says so with the
whole chain measured rather than inferred: the `MAJOR_MINOR` output is not merely complete, Flyway
13.3.0 applies it to a Postgres 18 and all three tables land.

The reply carries one shape that is **not** in the issue body — an independent table beside the
parent and its two children. Four tables in, two files out, three tables' DDL, and those two files
also share a version, so it is the only shape that trips both defects at once. It is there because a
regression test written over filenames would pass while a table was still missing; what holds is the
union of `CREATE TABLE` statements across every generated file. The probe is
[`probes/exposed-2897/`](../../probes/exposed-2897/README.md), committed because the first copy was
built on the Linux box and did not survive, and because the reply offers to run a candidate fix
through it.

What stays ours whatever the generator learns to do is `scripts/generate-migration.sh` and
`MigrationFilesTest`: a draft is renumbered by hand and checked.

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

**U11 is katcher#25 again, in the other toolkit, and it was found the same way.** konekt's client
records a degradation — the wire type of a component it could not draw — and the whole value of that
record is asking later WHICH type and how often. That is an indexed field in tracy. The published
listing under `ru/workinprogress/tracy/` is `agent`, `agent-jvm`, `agent-linuxarm64`, `agent-linuxx64`,
`agent-macosarm64` and the matching `shared-*`: no iOS, and no separate coordinate carrying it.
`macos_arm64` is the desktop host rather than the phone.

The consequence is not "one platform is less observed". It is that the blindness is worst exactly
where the feature matters most: a desktop build updates on our schedule and a phone updates on the
subscriber's, so an out-of-date client — the only thing that can meet an unknown component — is
likeliest on the platform that cannot report it.

`metrik` publishes the same four targets and no iOS. Not filed: what metrik measures is request
latency on a server, so an absent Apple target is a smaller claim than tracy's and one this build has
no use for yet. Recorded here so the next reader does not have to measure it again.

**U12 was found by building the thing and confirmed by the toolkit's own conformance kit**, which is
the closest this repository has come to the whole argument for it existing.

`FormPatch` updates values in the `FormController`; only bound components read it and every one of
them is editable; the only non-editable display, `read_only_field`, is explicitly unbound — its
renderer draws `component.value` and never touches the controller it is handed. So a price the server
computes as a form changes is either something a subscriber can type into or something a patch cannot
reach.

The first attempt worked around it by declaring the computed values as schema fields anyway and
rendering them as read-only text. That looked reasonable and is not: `form-fields` refused it on the
first walk — *field "price" is declared but never rendered* — because SPEC §9.2 asks that every
declared fieldId have a component rendering it. The workaround was an hour old, passed every test
written for it, and was caught by a check the toolkit ships for exactly this.

Worth noticing what that says about the order of events. The gap was **suspected** from reading the
sources, **confirmed** by trying to build around it, and **named precisely** by the kit. Any one of
the three alone would have been weaker: reading finds a shape, building finds the cost, and the kit
finds the rule being broken.

**U14 is the first one this project got wrong before getting it right, and the correction is the
finding.** The backlog item that led to it stated flatly that "kompot's modifier vocabulary has `Size`
and `Weight`, so a filled rounded group is not something the server can say". Reading
`kompot-core:0.33.1.91` rather than trusting that sentence: the vocabulary is `Background(color)`,
`Gradient(colors)`, `Padding`, `Size` and `Weight`, and the client resolves a `Background` through the
design system. Two thirds of what the item asked for already existed and had existed all along.

What is actually missing is one argument — the `Shape` passed to `Modifier.background`, which is
`null`. That is a much smaller ask than the one that would have been filed, and it is only visible to
somebody who opened the artefact. The premise of a task can be wrong, and a task whose premise is
wrong produces an upstream request that is wrong in the same direction.

**U17 is what was left over when U16 closed, and it is worth separating from it.** The side is now
right and the SPACING is not: the field draws `$ 50` and the limits line under it draws `$10`, from
one response and one currency. Whether there is a space is part of how a currency is written, next to
the symbol and the side — `MoneyFormat` already carries it as `spaceBeforeSymbol`, true for three of
its five currencies and false for the dollar — so a fixed gap is wrong in exactly the way a fixed side
was.

**There is no workaround this time**, and that is a difference worth recording rather than a gap in
the work. The side could be worked around because the symbol could be moved into the label, which is
a component the server composes; the gap belongs to the field's own layout and appears in no tree.
The screen is honest either way — nothing on it claims a spacing — so konekt draws the difference and
waits.

**U16 is the smallest of these and the one whose failure is quietest.** `AmountInputComponent` has a
`currencySuffix` and nothing else, so a server filling it from its own currency table is right for the
currencies written `10 €` and wrong for the ones written `$10` — and there is no third option. Nothing
fails: the field renders, the form submits, the amount is correct, and the symbol sits on the wrong
side of it.

What made it visible here was a screen writing the SAME currency twice. `MoneyFormat` holds a layout
per currency — symbol, side, separators — so the limits line under the field said "Between $10 and
$50,000" while the field said "50 $", six lines apart, in one response (`B-70`). Two of konekt's five
currencies are symbol-first, so neither half of the table could be hard-coded away.

The local answer left `currencySuffix` unset for a symbol-first currency and named it in the LABEL
instead — `Amount ($)` — which claimed no position and stayed visible once the label floated. It was
driven by the same table every other amount in this product is, and `AmountFieldPlacementTest` asserts
it over EVERY currency rather than over the deployment's: a hard-coded choice is right for half the
table, so a test written about `Currency.DEFAULT` would have agreed with the bug for the other half.

**That workaround is deleted.** `0.33.1.93` took the first of the two shapes the issue offered — a
`currencyPrefix` beside the `currencySuffix`, at most one set — which needed no new type and left
every existing screen where it was. **Both halves were checked in the artefact before the version was
bumped**, because a component that carries a field and a renderer that ignores it are the same green
build: `AmountInputComponent` declares `currencyPrefix`, and `AmountInputRenderer` reads it.

`MoneyFormat` lost its asymmetry with the workaround. It answers `leadingSymbol` and `trailingSymbol`,
exactly one of which is non-null for any currency — two questions rather than one returning a side, so
a caller cannot hold the answer and put it in the wrong field. The test that guards it did not change
shape: it still reads how the formatter writes a real amount in each currency and requires the field
to agree, still over the whole table, and still with the vacuity assertion that both branches ran.

**U15 is what `B-49` ran into on its last criterion.** The client resolves a deeplink through the
served `NavigationGraph` now, which is the whole point of that item — and one destination cannot go in
it. `app://order/<id>` is parameterised, and the conformance kit follows every route of a graph to its
endpoint EXACTLY as written: the prefix `/api/v1/screens/orders` is not a route and answered 404, and
the pattern `/api/v1/screens/orders/{orderId}` answered 404 as well, because nothing substitutes an id
for a graph route the way `TckConfig.pathParameters` does for an endpoint. Both measured against a
running stand rather than reasoned about.

So a graph carrying it is a graph the walk reports as broken, and a graph without it is a history whose
rows open nothing. konekt keeps that one deeplink client-side and says so where it is written; the
entry goes when the kit can be handed a value for a graph route.

Not filed yet — it wants a smaller reproduction than "our whole stand", and a parameterised
destination is common enough that the ask should propose the shape of the fix rather than only the
symptom.
