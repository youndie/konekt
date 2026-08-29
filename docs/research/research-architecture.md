---
id: research-architecture
title: konekt — architecture research
type: research
status: active
date: 2026-08-25
---

# Research: the architecture of konekt

`konekt` is a white-label subscriber account for an eSIM MVNO: the operator takes the box, rebrands
it, and gets a phone application and a server without writing a client. It is a reference build
rather than a product — every system outside its boundary (BSS/OCS, SM-DP+, the payment gateway, the
SMSC) is mocked on purpose, and what is real is the wiring: six toolkits, each carrying the load it
was written for, on a domain that loads them without contrivance.

This document records **verified facts** (read in source, in a published artefact, or in a registry
listing), **decisions taken**, and **risks**. Anything unverified is called a hypothesis and says
where it will be checked.

There is no code yet. "Verified" therefore means *read in the dependency at the version konekt will
pin*, not *read in konekt* — and that distinction is the whole reason this document exists before
the first commit: five of the eleven facts below contradict something the brief assumed.

What is built *on top* of the toolkits — versions, module layout, the layer rules inside a module —
is [research-stack](research-stack.md); decisions there are numbered from D11 so the two documents
share one sequence.

The brief is preserved verbatim as [source-draft](source-draft.md); it is in Russian and is not
edited, because a draft rewritten to agree with its research stops recording what was believed
first. The interface design is [design-app-canvas](../design/design-app-canvas.md). Upstream gaps
found here and their proposed issues are [research-upstream-proposals](research-upstream-proposals.md).

---

## 1. Verified facts

### 1.1 There is no such thing as "the stack version": six version lines, one of them tripled

Verified against `https://reposilite.kotlin.website/snapshots` — `maven-metadata.xml` per coordinate,
read on 2026-08-25 — and against `gradle.properties` in each repository at `HEAD`.

| Fact | Where verified |
|---|---|
| kompot publishes 20 coordinates, all at `0.30.0.72` | `io/github/youndie/kompot-*/maven-metadata.xml` |
| a kompot version is `kompot.version` plus the CI run number on the tail | `kompot/gradle.properties` (`kompot.version=0.30.0`) + README §Installation |
| petich is at `0.1.0.4`, all six modules | `io/github/youndie/petich-*/maven-metadata.xml` |
| booblik client is at `0.3.0`; `booblik-protocol` exists only from `0.3.0` | `io/github/youndie/booblik/*/maven-metadata.xml` |
| katcher runs **three** version lines: server `0.6.2`, `client` `0.5.1`, `client-android` and `android-gradle-plugin` `0.4.92` | `katcher/gradle.properties` + `ru/workinprogress/katcher/*/maven-metadata.xml` |
| metrik agent `0.1.13`, tracy agent `0.1.12`, viddik `0.1.2.13` | `ru/workinprogress/{metrik,tracy}/agent`, `ru/workinprogress/viddik-*` |
| every consumer of kompot or petich needs **Java 25** | `kompot/README.md` §Building, `petich/README.md` §Building |
| kompot builds on Kotlin `2.4.10`, Compose Multiplatform `1.11.1`, Ktor `3.5.2` | `kompot/gradle/libs.versions.toml` |

**Consequence 1.** kompot must be taken through `kompot-bom` and the version named once. Two
coordinates one CI run apart — `kompot-core:0.30.0.71` beside `kompot-client:0.30.0.72` — resolve
quietly into a combination nobody ever built, and the tail digit makes that trivially easy to write
by accident. The platform makes it impossible to write down at all.

**Consequence 2.** katcher cannot be pinned with one version property. A version catalogue that
carries `katcher = "0.6.2"` and uses it for the client resolves nothing, and the error will name a
coordinate rather than the mistake. Three separate entries, each commented with what it versions.

**Consequence 3.** Everything is a snapshot repository with no release line. `konekt` therefore
gates a milestone on *all* coordinates resolving in one clean build, not on the first artefact
appearing — a version can be half-published while CI is still running, and the failure reads as
"Could not find" halfway through an otherwise green pipeline.

### 1.2 The wire has no vocabulary for shape, and that is deliberate

Verified against kompot `0.30.0` sources.

| Fact | Where verified |
|---|---|
| `kompot-core` declares exactly two token kinds: `ColorToken`, `TypographyToken` | `kompot-core/src/commonMain/kotlin/io/github/youndie/kompot/` |
| the modifier vocabulary is `Background`, `Gradient`, `Padding`, `Size`, `Weight` — no radius, border or elevation | same package, `*Modifier*.kt` |
| `KompotTheme` carries `id`, `light`/`dark` palettes and `typography`, and nothing else | `kompot-theme/.../KompotTheme.kt` |
| `KompotPalette` is `Map<String, String>` of hex colours; `KompotTextStyle` is size, line height, weight, letter spacing and colour | same file |
| shape reaches a renderer through `KompotDesignSystem.resolveSurface(role)` returning `KompotSurface(shape, container, content, outline, textStyle)` | `kompot-client/.../DesignSystem.kt`, `kompot-client/.../Surface.kt` |
| a `SurfaceRole` is documented as a client-side key that never travels | `kompot-client/.../Surface.kt`, the comment above `SurfaceRole` |
| `dark = null` means the client stays on its built-in palette rather than reusing the light one | `kompot-theme/.../KompotTheme.kt` |

**Consequence 1.** The design's note — *"radii are a client build constant; the server theme carries
colours and typography only, so brand B's shape change needs a client release"* — is correct, and it
is correct by design rather than by omission. kompot protects the property that the server names
nothing about appearance. Brand B in the canvas differs from brand A by radii alone (36→22, 20→12,
pill→rounded rectangle), so that one axis of the rebrand is the one axis that cannot ship from the
server.

**Consequence 2.** The brief's success metric — *"a change of brand (theme + copy) with no client
rebuild"* — is true for colour and typography and false for shape. It is restated in D2 rather than
quietly kept.

**Consequence 3.** The brand kit is therefore two artefacts with different lifetimes: a colour and
type kit served from `/theme`, and a shape scale compiled into the client. Anything in the operator
onboarding material that promises otherwise is wrong before it is written.

### 1.3 A server-driven theme silently discards every surface role — a defect, not a boundary

Verified against kompot `0.30.0` sources.

| Fact | Where verified |
|---|---|
| `RemoteThemeDesignSystem` overrides `resolveColor` and `resolveTypography` only | `kompot-theme-client/.../RemoteThemeDesignSystem.kt` |
| it therefore inherits the interface default `resolveSurface(role) = KompotSurface()` and never consults its own `fallback` | `kompot-client/.../DesignSystem.kt:26` |
| the class documents itself as *"an overlay, not a replacement"* that falls back for what the theme did not describe | `RemoteThemeDesignSystem.kt`, header comment |
| three renderers read the hook: button, `text_input`, `read_only_field` | `kompot-client/.../Components.kt:198`, `kompot-forms-client/.../FormRenderers.kt:47,84` |
| no production class overrides `resolveSurface`; the only overrides in the repository are screenshot-test doubles | `grep -rn resolveSurface` across kompot |

**Consequence.** An application that customises `resolveSurface` on its own design system and then
wraps it in `rememberKompotDesignSystem(theme, fallback)` loses that customisation **the moment the
theme arrives** — buttons return to Material's pill, fields get their borders back. Nothing throws
and nothing logs; the first frame is right and the second is wrong.

**Fixed upstream, 2026-08-25 — [kompot#80](https://github.com/youndie/kompot/issues/80), released in
`0.31.0.74`.** `RemoteThemeDesignSystem` now carries
`override fun resolveSurface(role) = fallback.resolveSurface(role)`, verified in source. The facts
above are left standing because they are what was true of `0.30.0`, and because the shape of the bug
is worth keeping: an overlay that forwards two of three hooks is a class of mistake, not an incident.
What changes for konekt is D3 — the inverted wrapping is no longer needed, and the composition root is
written the ordinary way. The screenshot test survives the workaround it was written for and now
guards the toolkit.

### 1.4 An unknown component is invisible from both sides

Verified against kompot `0.30.0` sources.

| Fact | Where verified |
|---|---|
| an unregistered wire type decodes to `UnknownComponent(originalType, fallback)` instead of throwing | `kompot-core/.../UnknownComponent.kt` |
| the server may name a stand-in through `fallback`, which is itself an ordinary component | same file |
| with `fallback == null` the renderer calls `println` and returns — **nothing is drawn** | `kompot-client/.../Components.kt:507-523` |
| the registry is a plain `Map<KClass, KompotComponentRenderer>`, so an application can replace the entry for `UnknownComponent::class` | `kompot-client/.../Components.kt:527` |

**Consequence 1.** The canvas is explicit that this must never be a hole — *"the server sent a
component this build does not know; everything around it still works"*, in two densities, *"never a
blank gap"*. konekt registers its own `UnknownComponentRenderer`. That is a supported extension
point, so this is not a blocker.

**Consequence 2.** `println` is: it reaches neither tracy nor a katcher breadcrumb, and on a phone it
reaches nobody at all. A deployment that ships a newer server against an older client learns about it
from store reviews.

**Fixed upstream and wider than asked, 2026-08-25 — [kompot#81](https://github.com/youndie/kompot/issues/81),
released in `0.31.0.74`.** `KompotDegradationSink` reports three kinds — `UNKNOWN_COMPONENT`,
`UNKNOWN_ACTION` and `UNRENDERABLE_COMPONENT` (a type that decodes but has no renderer in this
build's registry, a case konekt had not thought to name) — with a `drawnAsFallback` flag separating a
hole from a placeholder. The default is still the `println`, so nothing moves for a deployment that
sets no sink. The *drawing* half of Consequence 1 is unchanged and stays konekt's: the toolkit still
draws nothing without a server-named fallback, which is the right default and the wrong one for this
product.

### 1.5 What kompot's component dictionary already contains, and what konekt has to own

Verified by reading every `@SerialName` in the published protocol modules.

| Module | Wire types |
|---|---|
| `kompot-standard` | `column`, `row`, `text`, `button`, `table`, `paginated_list`; actions `navigate`, `open_url`, `close`, `copy_text`, `load_page` |
| `kompot-forms` | `text_input`, `amount_input`, `autocomplete_input`, `checkbox_input`, `radio_group`, `select_input`, `read_only_field`, action `submit_form` |
| `form-standard` | fields `text_field`, `amount_field`, `checkbox_field`, `autocomplete_field`, `selection_field`; values `text_value`, `amount_value`, `boolean_value`, `entity_value`; rules `required`, `required_if`, `regex`, `equals`, `not_equals`, `max_amount_from_field` |
| `kompot-auth` | one action: `update_session` |

**Consequence 1.** Every form frame in the canvas maps onto an existing `kompot-forms` type — except
the `switch`, which had no wire type.

**Closed upstream, 2026-08-25 — [kompot#82](https://github.com/youndie/kompot/issues/82).**
`CheckboxInputComponent` gained `variant: String?`, an open string like a button's, with
`KompotCheckboxVariants.SWITCH` the one word the standard renderer acts on and anything else degrading
to a checkbox. So a switch is a toolkit component after all, and konekt's own dictionary is **nine**
components rather than ten.

**Consequence 2.** The canvas's dictionary section names nine things the toolkit does not have:
counter card with progress, plan card, QR block, eSIM card, order row, banner (info / low / error),
snackbar, step meter, and skeleton. Those are konekt's own components, in one KSP module with its own
`kompotModuleTag`, and they are the reason konekt has a client release cycle at all.

**Consequence 3.** `kompot-auth` is one action, not a session system. The brief's *"session through
kompot-auth"* oversells it by a wide margin: OTP issue and check, token storage, refresh and logout
are all konekt's. Recorded as a deviation in D4.

### 1.6 kompot ships the realtime contract and refuses to choose a transport

| Fact | Where verified |
|---|---|
| `kompot-realtime` is three declarations: `KompotRealtimeSource`, `UpdateComponentMessage`, `KompotScreenResponse` | `kompot-realtime/src/commonMain/` |
| `kompot-realtime-server` is a broadcaster plus a bus contract; the default bus is in-memory | `kompot-realtime-server/src/commonMain/`, README §Modules |
| *"it does not choose a transport — the SSE or WebSocket implementation is yours"* | `kompot/README.md` §What it does not do |
| `kompot-ktor`, `kompot-realtime-server` and `kompot-forms-standard` publish for JVM only | `kompot/README.md` §Targets |

**Consequence.** The live counters and the live order status in the canvas are konekt code on both
ends: an endpoint on the server and a `KompotRealtimeSource` on the client. One server process means
no Redis and no `kompot-realtime-redis`. The three JVM-only modules also settle a layout question
before it is asked — the server-side form DSL cannot live in a module shared with the client.

### 1.7 petich guarantees compensation; it does not guarantee that anyone is told

| Fact | Where verified |
|---|---|
| the phase order is fixed: `ENRICHMENT → VALIDATION → AUTHORIZATION → EXECUTION → POST_PROCESSING`, priority-ordered within a phase | `petich/README.md` §What it solves |
| a step may return `InterceptorResult.Suspend(requiredAction, ttl)`; a sweeper rolls back a wait nobody returned to | same, §What it looks like |
| with a repository that is not outbox-aware **the engine falls back to a plain update and drops the events** | same, §What it solves, final bullet |
| petich delivers nothing itself: the transport is the application's | same, §What it does not do |
| no DDL, no migrations, no driver, no connection pool — `petich-postgres` takes an Exposed `Database` | same, §Installation |
| a six-interceptor saga costs ≈17 database writes, a four-interceptor one ≈9, measured through `pg_stat_user_tables` | same, §Cost |
| `PetichEngineMetrics` counters exist and are a no-op by default | same, §Observability |

**Consequence 1.** The whole demonstration chain — form → saga → outbox → booblik → realtime — hangs
on one wiring decision, and the failure mode is silence. A saga test that asserts the saga reached
`COMPLETED` passes identically whether the event was written or dropped. The gate is therefore a test
that reads the **outbox row** inside the committed transaction, not one that waits for a message. See
Risk 1 and [U4](research-upstream-proposals.md#u4).

**Consequence 2.** Schema, migrations, pool and driver are konekt's: Postgres, Flyway, HikariCP,
Exposed.

**Consequence 3.** The purchase saga is designed at four steps, not six, and the 9-vs-17 figure is
why (D5).

### 1.8 booblik's topics are fixed at startup, and the wire is plaintext

| Fact | Where verified |
|---|---|
| *"Topic creation is not coming: the set of partitions is fixed at startup on purpose"* | `booblik/README.md` §Overview |
| neither TLS nor compression is coming — both are incompatible with the zero-copy path | same |
| `booblik-client` is a JVM source set (`src/main/kotlin`), published as `io.github.youndie.booblik:booblik-client` under package `ru.workinprogress.booblik` | `booblik/booblik-client/src/main/kotlin/`, registry listing |
| a subscription is a `Flow<RecordBatch>`; a caught-up consumer waits on the broker rather than polling | `booblik/README.md` §Overview |

**Consequence 1.** `orders`, `usage` and `notifications` are declared in the broker's configuration
and shipped in the compose file. A new topic is a broker restart, which makes topic naming an
architectural decision rather than a runtime one.

**Consequence 2.** No TLS means the broker never leaves the compose network. That is fine for a
reference build and is stated rather than assumed.

### 1.9 The observability trio covers the server; iOS is covered now, and Android is not

> **Amended, and the finding is now the opposite.** Both gaps were upstream and both are closed:
> katcher publishes every Apple target since `client:0.6.2` ([katcher#25](https://github.com/youndie/katcher/issues/25))
> and tracy publishes the three iOS targets since `0.1.13` ([tracy#16](https://github.com/youndie/tracy/issues/16)).
> A simulator crash arrives in katcher naming its release, and a screen the client cannot draw is
> findable in tracy by wire type — both measured at the collector rather than at the agent. What
> remains uncovered on iOS is metrik, which measures route latency and has no routes to measure there.
>
> The section is kept rather than rewritten because the reasoning below is what made the two gaps
> legible enough to file, and filing them is what closed them. See `B-26` and `B-27`.

| Fact | Where verified |
|---|---|
| metrik is a Ktor plugin: `install(Metrik) { service, apiKey, endpoint, release }`, ingest over UDP `:9999` | `metrik/README.md` |
| tracy is an agent plus two plugins (`Tracy` on the server, `TracyClient` on the outgoing `HttpClient`); logging is `suspend` because Kotlin/Native has no MDC | `tracy/README.md` §Quick start |
| tracy fields carry `indexed = true` to become entity keys | same |
| katcher `client:0.5.1` publishes **`jvm` and `linux_x64` only** — the Gradle module metadata names those two variants and no others | `ru/workinprogress/katcher/client/0.5.1/client-0.5.1.module` |
| Android is a separate coordinate, `client-android:0.4.92`, plus `android-gradle-plugin:0.4.92` which uploads the R8 mapping | registry listing, `katcher/README.md` §Android integration |
| the `client` module declares `jvm()` and one host-dependent native target; no Apple target is declared | `katcher/client/build.gradle.kts:21-41` |

**Consequence, as of `client:0.5.1`.** konekt's iOS build had no crash reporting from this stack, and
that was named as a gap in D8 rather than papered over with a third-party SDK.

**Fixed upstream, 2026-08-25 — [katcher#25](https://github.com/youndie/katcher/issues/25),
released as `client:0.6.2`.** The published module metadata now names `ios_arm64`,
`ios_simulator_arm64`, `ios_x64`, both macOS targets, both Linux targets, `mingw_x64` and `jvm`; the
host-picked native target is gone, which was the second and smaller ask in the same issue. D8 is
therefore withdrawn — the gap it described no longer exists — and `B-27` changes from writing the gap
down to wiring the client up. One consequence for the build files: the client and the server now share
the repository's own version, so the three katcher entries in the version catalogue are two.

Kept as a fact rather than deleted, because it is the reason `B-27` exists at all and because "the
published targets are whichever host built them" is a shape worth recognising again.

**Amended again by `B-85`: the Android half is a third gap, and it is the largest of the three.** The
row above says "Android is a separate coordinate" as if that settled it. It does not, and building an
Android client is what showed why — three facts, all measured on this build and on a Pixel 6a:

| Fact | Where verified |
|---|---|
| `kompot-client` resolves its ANDROID variant, `androidApiElements-published` with `libraryelements = aar` and `platform.type = androidJvm` | `./gradlew :client:dependencyInsight --configuration androidCompileClasspath --dependency kompot-client` |
| katcher's multiplatform `client` still declares no android target, so an Android consumer resolves `client-jvm` — silently, since nothing fails to resolve or compile | `katcher/client/build.gradle.kts`, and the resolved artefact |
| `client-android:0.4.92` declares `object Katcher` in the SAME package as `client`, so the two fail `checkDebugDuplicateClasses` on one classpath | the AGP failure, quoted in `androidApp/.../CrashActivity.kt` |
| `JvmKatcherFileSystem` caches at `File(System.getProperty("user.dir"), ".katcher_cache")`; on Android that is `/`, and Android **refuses** an application's attempt to change the property | device log: `Ignoring attempt to set property "user.dir"`, then `Failed to save crash report: /.katcher_cache/…: ENOENT` |

**Consequence, and it is the first row of the observability table this repository cannot turn green
from inside.** The Android build's crash hook fires and reaches katcher's own handler; the report is
never stored and therefore never uploaded, and `start` prints *"Storage ready"* having checked
nothing. There is no workaround available to a consumer: one artefact cannot be used and the other
cannot be fixed from outside. Filed as
[katcher#27](https://github.com/youndie/katcher/issues/27), with the device measurement.

**The kompot row is the opposite result and worth the same weight.** kompot's README records an
Android consumer silently resolving the DESKTOP variant; konekt is the second implementation able to
check, and the `.aar` arrives. That is a gap closed and confirmed rather than assumed.

### 1.10 Conformance needs an OpenAPI document, and a clean report can mean nothing was checked

| Fact | Where verified |
|---|---|
| `kompot-tck` walks a **running** server: `TckRunner(RemoteTckTransport(url), TckConfig(schemas, openApi))` | `kompot/README.md` §The wire specification |
| it reads endpoint kinds out of the deployment's OpenAPI document and assumes no addresses | same |
| *"a check that found nothing to apply to passes silently, and that is the commonest way to end up with a conformance kit that proves nothing"* — the report prints how many targets each check visited | same |
| an application assembles its own spec: `KompotSpec.generateAll(KompotToolkitSpec.modules + myComponentsSpecModule())` | same |

**Consequence.** Two obligations, both easy to skip and both worth a backlog item. konekt publishes
an OpenAPI document as a build artefact, and the CI gate asserts **per-check target counts above
zero**, not `report.isClean`. A green TCK on a server whose screens the walk never reached is the
exact failure the toolkit's own author warns about.

### 1.11 `call.respond` on a component tree drops the root's type discriminator

| Fact | Where verified |
|---|---|
| a plain `call.respond(component)` resolves the serialiser from the concrete runtime class and omits `"type"` on the **root**; nested children are unaffected | `kompot/README.md` §What it looks like |
| the supported call is `call.respondKompotComponent(...)` | same |

**Consequence.** The client receives an unknown component for the whole screen, and by §1.4 draws
nothing — a blank screen from one wrong call, with children that would have serialised perfectly.
konekt forbids `call.respond` on a `KompotComponent` by convention and catches it in review; the TCK
walk catches it in CI, since an empty root fails the walk.

### 1.12 The wizard is two modules, and only one of them fits a flow without forms

| Fact | Where verified |
|---|---|
| `wizard-core` is the step machine: `WizardEngine.transition(session, transition, draft)` is a pure function and the module depends on nothing else in the toolkit | `kompot/wizard-core/src/commonMain/.../WizardEngine.kt` |
| `WizardScreenComponent` requires a non-null `formId`, and its own comment says it is "the same id as the FormSchema of the content inside" — a renderer needs it to build `NextStepAction(formId)` | `kompot/kompot-wizard/src/commonMain/.../Components.kt` |
| `WizardResumeRequest.values` is a `Map<String, FieldValue>`, `FieldValue` being form-core's field contract | `kompot/kompot-wizard/src/commonMain/.../WizardResumeRequest.kt` |
| the engine has no notion of a refused transition: `Next` either moves to the resolver's answer or, on `null`, stays put | `WizardEngine.kt`, verified again in `EsimWizardGraphTest` |

**Consequence, and it splits the module in two.** The step machine is usable by anything; the wire
half of `kompot-wizard` presupposes a form. An install flow whose steps are "read this and continue"
has no `FormSchema` to name, so `formId` would be a value invented to satisfy a field — and the
client's wizard renderer would then build its Back and Next actions from it. konekt therefore takes
`wizard-core` for the graph and draws the chrome itself, out of `step_meter`, which the design canvas
already asked for ("the wizard's own progress, not a generic progress bar"). When a genuinely
form-shaped flow arrives — `B-20`, the package builder — the wire half becomes the right tool and
nothing here blocks using it there.

**Second consequence, larger.** Because the engine cannot refuse, every rule of the shape "not from
here, not yet" lives *outside* the transition, in the use case, and runs before it. That is what makes
the canvas's slot-limit frame reachable at all: a refusal expressed as an exception is a status code
with no screen behind it, and the wizard is then neither on the step it refused nor on the next one.
`AdvanceEsimWizardUseCase` gates first and answers with the same step plus a reason.

### 1.13 kompot actions are registered by hand, and nothing notices when they are not

| Fact | Where verified |
|---|---|
| `@KompotComponentMarker` plus the KSP processor generate the polymorphic registration for **components** | `kompot/kompot-registry-processor`, and `KonektRegistrationTest` |
| the `KompotAction` hierarchy has no generator: `:kompot-wizard` registers its three subclasses by hand in `kompotWizardSerializersModule` | `kompot/kompot-wizard/src/commonMain/.../Serializers.kt` |

**Consequence.** An application that adds an action of its own and forgets to add its module to the
application's `Json` compiles, starts, and draws every screen correctly — the encode side is fine.
The failure is on the way back in, at runtime, on the one request the action exists for, and it
arrives as a 400 or a 500 on a body the server itself wrote. konekt's one action, `esim_wizard_step`,
is covered by `EsimWizardActionTest` for the round trip and by `EsimWizardRoutingTest`, which drives
the whole wizard by posting back the buttons the server drew rather than by composing requests.

### 1.14 The toolkit's wire is multiplatform and its renderers are not — iOS has no Compose client

| Fact | Where verified |
|---|---|
| `kompot-client`, `kompot-theme-client`, `kompot-ds-material-compose`, `kompot-forms-client`, `kompot-wizard-client` and `kompot-images-client-coil` publish `-android`, `-desktop` and `-wasm-js` and **no iOS artefact** | the published artefact names under `io/github/youndie` at `0.31.0.74` |
| the same six declare `jvm("desktop")`, `androidLibrary { }`, `wasmJs { browser() }` and no Apple target | each module's `build.gradle.kts` |
| every protocol module — `kompot-core`, `kompot-standard`, `kompot-forms`, `kompot-wizard`, `wizard-core`, `kompot-theme`, `kompot-navigation`, `kompot-client-cache` — does publish the three iOS targets | same listing |
| the README states that "every protocol and client module publishes for JVM, Android, the three iOS targets and `wasmJs`" and names three deliberate exceptions, none of which is one of the six | `kompot/README.md` §Targets |
| `org.jetbrains.compose.runtime:runtime-iosx64` was last published at `1.11.0-alpha01`; `runtime-iosarm64` and `runtime-iossimulatorarm64` are current at `1.12.0` | `repo1.maven.org` maven-metadata, 2026-08-25 |

**Consequence, and it reaches the product rather than the build.** konekt's brief says the client is
Compose Multiplatform on **Android and iOS**. The Android half is available; the iOS half is not, and
no amount of code here changes that — the renderers are the toolkit's. What exists for iOS instead is
`kompot-swift-interop`, which is a bridge for a **native SwiftUI** client rather than a Compose one,
and that is a different product with a different client codebase.

**Closed and released, 2026-08-25.** [youndie/kompot#84](https://github.com/youndie/kompot/issues/84)
was fixed in `0.31.0.76`, and the fix was checked here rather than read off the issue: at `0.32.0.77`
the module metadata of `kompot-client`, `kompot-theme-client` and `kompot-ds-material-compose` each
declares `ios_arm64` and `ios_simulator_arm64`. `:client` now compiles for both.

The second half of the paragraph above outlived the first exactly as predicted: the reachable set is
**two** iOS targets, not three. Compose dropped `iosX64`, the toolkit's Compose half shows the same
pair, and its protocol half (`kompot-core`, `kompot-realtime`, `kompot-auth`) still ships all three.
So `konekt.multiplatform` — which every other multiplatform module here uses — still cannot be the
client's, and `:client` names its own targets. The reason it is not the convention plugin has changed
completely while the conclusion has not, which is why the build file now says which reason it is.

### 1.15 A live update is an overlay that nothing ever clears, and the cache cannot tell anyone it refreshed

Verified on 2026-08-25 against `kompot-client-cache`, `kompot-realtime` and `kompot-client` at
`0.31.0.74` — the pinned version — by reading the published artefacts. This is the observation
[open question 1](#3-risks-and-open-questions) asked for; the decision it produced is
[B-18](../backlog/B-18-cache-versus-realtime.md).

| Fact | Where verified |
|---|---|
| `CachedKompotScreenProvider.getScreen(key)` is one-shot: on a hit it launches `revalidate` into its scope and **returns the stored payload immediately** | `kompot-client-cache-jvm-0.31.0.74`, `CachedKompotScreenProvider.getScreen` |
| `revalidate` writes `store.put(...)` on `Modified`, nothing on `NotModified`, and returns `Unit` — **no flow, no callback, no listener** | same class, `revalidate` |
| `CachedScreenEntry` carries `fetchedAt` and **nothing reads it**: no TTL, no expiry | same module; `getFetchedAt` has zero references in the provider |
| the module depends on `kompot-core`, `kotlinx-serialization-json`, `kotlinx-coroutines-core` and **not on ktor** — `KompotScreenFetcher` is an interface, so the conditional request and the `ETag` are the application's | the module's `.module` metadata |
| `UpdateComponentMessage` is `(componentId, component)` and `KompotScreenResponse` is `(screen, realtimeTopic)` — **neither carries a version, a sequence or a timestamp** | `kompot-realtime-jvm-0.31.0.74` |
| `KompotRealtimeProvider` collects the source into `remember(topic) { mutableStateMapOf() }` — **keyed by topic, and by nothing else** — and provides it as `LocalKompotRealtimeUpdates` | `kompot-client-desktop-0.31.0.74` sources jar, `Realtime.kt` |
| `CachedKompotScreenProvider` has a **second** public entry point, `suspend invalidate(key)` = `store.clear(key)`, whose own comment names the post-mutation case: revalidation "hands the result to the NEXT getScreen" | `kompot-client-cache-jvm-0.31.0.74` sources jar, `CachedKompotScreenProvider.kt:46` |
| `KompotRegistry.RenderNode` draws `LocalKompotRealtimeUpdates.current[node.id] ?: node`, choosing the renderer from the **replacement's** class | same artefact, `KompotRegistry.RenderNode` |
| nothing in that provider removes a map entry; the only eraser is the composition being discarded | same file — the map is written by `collect` and read by the composition local, and there is no other access |

**Consequence 1 — the hypothesis was half right, and the wrong half is the load-bearing one.** The
cache does store the screen as fetched and updates do apply on top in memory. But "a cold start shows
the stale value for exactly one request" assumed something re-asks; nothing does. `getScreen` answers
once and `revalidate` cannot deliver, so the stale screen stays on display until the screen is
re-entered.

**Consequence 2 — an update is not a replacement in a tree, it is an overlay above every tree.**
Because the map is keyed by component id alone, an entry recorded before a stream gap keeps shadowing
the correct component of a screen fetched after it — with a healthy network, a fresh fetch and no
error anywhere. The map does have one eraser, `remember(topic)`, and it does not reach this case:
konekt serves one topic per subscriber and `SseRealtimeSource` reconnects **inside** one flow, so the
topic never changes and the key never fires. The overlay therefore lives as long as the composition. That defect belongs to the
realtime half by itself: a build with no cache at all reproduces it, which is why the alternative of
disabling the cache would have hidden it rather than fixed it. What ends it is clearing the overlay on
`streamRestarted`, which is what that signal was built for.

---

## 2. Decisions

### D1. One repository, `konekt`, docs inside it

Brief: unstated. Decision: server, client and `docs/` in `github.com/youndie/konekt`, public.

Why:

- one product, one deployable pair, one release cadence — the condition under which a separate
  documentation repository earns its keep (three or more service repositories with a feature smeared
  across them) is not met;
- the client and the server share the component module: the same `@KompotComponentMarker` classes are
  serialised on one side and rendered on the other, and splitting the repository would put a
  publication step in the middle of the tightest loop in the project;
- the price: the Android and iOS build and the server build sit in one Gradle build, so a server-only
  change still evaluates the client's configuration. Acceptable at this size; revisit if
  configuration time passes a minute.

The name is `konekt`, one `n`, because the canvas and the activation code in it
(`LPA:1$rsp.konekt.io$…`) already spell it that way and an activation code is the one string a
subscriber reads character by character.

### D2. A brand kit is colour and typography from the server, shape from the client *(deviation from the brief)*

Brief §8: *"a change of brand (theme + copy) — without rebuilding the client"*.
Decision: colour, typography and every string ship from the server; the shape scale is a client build
constant, and brand B in the canvas needs a client release for its radii.

Why:

- §1.2 — the wire has no shape vocabulary and kompot protects that deliberately; `SurfaceRole` is
  documented as a client-side key that never travels;
- inventing one in konekt would mean a fork of `kompot-core`, which is exactly the failure this
  project exists to avoid demonstrating;
- the price: honesty in the operator-facing material. Two brands that differ only in palette are a
  configuration change; two that differ in shape are a release. The canvas already proves the layout
  survives the shape swap, which is what makes the client-side constant cheap rather than dangerous.

**Amended by `B-83`: "typography" here means the type SCALE and not the face.** §1.2's own table
records it — `KompotTextStyle` is size, line height, weight, letter spacing and colour, and no family
— so a font family is not a server-side axis at any price, and this decision never made it one. The
scale itself could travel, in a kit's `typography` block; neither kit in this build carries one, so
the half of D2 that has actually been exercised is colour. `operator-boundaries.md` prices the three
separately.

### D3. konekt ships its own renderer for `UnknownComponent`

Brief: unstated. Decision: replace the toolkit's entry for `UnknownComponent::class` with a renderer
drawing the canvas's two densities, and route the event to tracy and to a katcher breadcrumb.

Why: §1.4 — the default draws nothing and reports through `println`. The registry is open, so this
costs one map entry and no fork. The upstream ask was narrower than the local fix, on purpose.

**Amended 2026-08-25.** The reporting half is now the toolkit's: konekt supplies a
`KompotDegradationSink` into tracy and a katcher breadcrumb instead of writing its own reporting into
its own renderer, and gets `UNRENDERABLE_COMPONENT` and `UNKNOWN_ACTION` for free. The drawing half is
unchanged and still ours.

### D4. Authentication is konekt's, not the toolkit's *(deviation from the brief)*

Brief §3: *"Authorisation — number + OTP, session through kompot-auth"*.
Decision: `kompot-auth` contributes the `update_session` action and nothing else. OTP issue, OTP
check, rate limiting, token storage, refresh and logout are konekt code behind a Ktor `Authentication`
provider.

Why: §1.5 — `kompot-auth` is a single serialisable action. Recording this now is worth more than it
looks: a backlog written from the brief would have carried an item called "wire up kompot-auth" and
sized it at a day.

### D5. The purchase saga is four interceptors

Brief §5: reserve → activate in the mock BSS → charge → emit, plus a confirmation step.
Decision: `VALIDATION` (balance and plan availability), `AUTHORIZATION` (hold, then `Suspend` for
confirmation), `EXECUTION` (charge and provision), `POST_PROCESSING` (emit) — four, with the hold and
the confirmation in one interceptor rather than two.

Why: §1.7 — 9 writes against 17, measured, and the saga table is the hottest row in the system
because every step boundary writes to it. The rejected alternative splits hold and confirm for
readability; it costs about 8 extra writes on the most frequent operation in the product to make one
interceptor easier to read.

### D6. The outbox-to-booblik bridge is konekt code, and its absence is a failing test

Brief §5: *"the petich outbox publishes into booblik"*.
Decision: correct, and the publisher is konekt's — petich supplies the mechanism only (§1.7). It
ships with a test asserting that a committed saga leaves a row in the outbox table.

Why: the fallback is silent. The rejected alternative is an end-to-end test that waits for a message
on the topic; it is slower, it is flaky, and it fails for a dozen reasons that are not this one.

### D7. Server-Sent Events, not WebSocket

Brief §5: *"kompot-realtime (+server) — live counters and order status"*, transport unstated.
Decision: SSE over one endpoint, in-memory bus, no Redis.

Why: the traffic is one-directional (`UpdateComponentMessage` server→client), SSE survives proxies
that mishandle upgrades, and reconnection with `Last-Event-ID` is in the protocol rather than in our
code. One server process means the in-memory bus is the whole requirement. The price: no client→server
channel, which the product does not need — every subscriber action is already an HTTP action.

### D8. iOS crash reporting is a stated gap, not a third-party SDK *(withdrawn 2026-08-25)*

Decision: v1 collects no crashes from the iOS build. It is written in the service document and in the
README, and raised upstream ([U5](research-upstream-proposals.md#u5)).

Why: §1.9. Adding Sentry or Crashlytics to a build whose purpose is to exercise this stack would
answer the wrong question and hide the finding. An empty answer that names itself is worth more here
than a full one from somewhere else.

**Withdrawn.** katcher `client:0.6.2` publishes all three iOS targets, so the gap this decision was
managing is gone and there is nothing left to state. Kept rather than deleted because the reasoning
outlives the case: refusing to hide a gap behind another vendor's SDK is what made the gap legible
enough to file, and filing it is what closed it in one day. `B-27` becomes wiring rather than
documentation.

### D9. Upstream gaps go out as issues; konekt never forks a toolkit module

Decision: every gap this project finds in kompot, petich, booblik, katcher, metrik or tracy is filed
as an issue on that repository. Where a gap blocks work, konekt works around it in its own code, and
the workaround carries a comment naming the issue so that it can be removed rather than inherited.

**Amended 2026-08-25**: a repository that is not ours is asked about first. `youndie/*` is the
working arrangement below and needs no permission; anything else — JetBrains/Exposed, Ktor, a
third party — gets the finding written into
[research-upstream-proposals](research-upstream-proposals.md) and a question, not a filing. A report
into somebody else's tracker spends their time and cannot be quietly withdrawn, and the first one
sent out from here named a symptom as a mechanism.

Why: this is the instruction under which the research was done, and it is also the arrangement that
makes the reference build worth anything to the toolkits — a second implementation reading a
published contract finds what the author cannot, and an issue records the finding where the next
reader will look. A fork moves the finding into a private diff and it dies there. The workaround
comments matter as much as the issues: a workaround copied into the next project outlives the illness
it was written for.

### D10. External systems are mocked in-process, behind interfaces, with injectable failure

Brief §3: BSS/OCS, SM-DP+, the payment gateway and the SMSC are mocked.
Decision: in-process modules behind the same interfaces a real integration would implement, each with
a configuration switch for always-succeed / refuse / delay.

Why: the demonstration's payload is compensation and rollback, and neither can be shown without a
refusal on demand. Separate mock processes would add operational surface without adding anything to
what is being shown; the interface boundary is what keeps the swap honest.

---

## 3. Risks and open questions

**Risk 1. The petich outbox falls back silently, so the entire event chain can be absent while every
test is green.** The engine drops events when handed a repository without outbox support and says
nothing (§1.7). Mitigation: the wiring test of D6 asserts an outbox row exists after a committed
saga; and a startup assertion refuses to boot when the configured repository does not implement the
outbox interface, because a check that runs at request time runs after the damage. Open: whether
`PetichEngineMetrics` can carry a dropped-event counter, which is [U4](research-upstream-proposals.md#u4).

**Risk 2. The TCK can pass without checking anything.** A walk that reaches no screens reports clean
(§1.10). Mitigation: the CI step parses the per-check target counts and fails when any check visited
zero targets — the assertion is on coverage, not on the verdict.

**Risk 3. Every dependency is a snapshot, and a version can be half-published.** Mitigation:
`kompot-bom` for kompot; for the other five, a milestone closes only after a clean resolve from an
empty Gradle cache, run after the upstream CI finished rather than after the first artefact appeared.

**Risk 4 — retired 2026-08-25. Surface customisation vanishes when the theme arrives** (§1.3). Closed
by [kompot#80](https://github.com/youndie/kompot/issues/80) in `0.31.0.74`, so the planned mitigation —
inverting the wrapping — is not built. **The screenshot test is still built**: it takes the same screen
before and after the theme arrives and fails on any difference outside colour and type. A guard written
for a fixed bug is what notices the regression, and this one costs a golden pair.

**Risk 5. The forward-compatibility frame in the canvas cannot be demonstrated by the build that
draws it.** `esim_transfer_widget` is unknown only to a client that does not register it, and the
client in the repository will register everything the server sends. Mitigation: a deliberate fixture —
one server route that emits a type absent from the client registry on purpose, reachable in the
demo build only. Without it the frame is a picture of a state the product can never enter.

**Risk 6. booblik is one process with no replication, and its topics are fixed at startup** (§1.8).
For a reference build this is honest; for a boxed product it is a stated limitation, and it belongs in
the operator-facing material rather than in a footnote.

**Open question 1 — answered 2026-08-25, and the hypothesis was half wrong.** *How does
`kompot-client-cache`'s ETag revalidation interact with a realtime `UpdateComponentMessage` — does a
cached screen replayed from disk carry a component that a live update has already replaced?
Hypothesis: the cache stores the screen as fetched and the update applies on top in memory, so a cold
start shows the stale value for one request.*

Confirmed: the cache stores the screen as fetched and never sees an update; updates apply on top, in
the composition. **Refuted: "for one request."** `getScreen` answers once and its background
revalidation has no way to hand the refreshed screen back, so the stale value stays until the screen
is re-entered. And the reading found a second interaction nobody was looking for, in the direction
opposite to the one feared: because the update overlay is keyed by component id and is never cleared,
a pre-gap entry shadows a correct post-fetch component permanently. The observation is §1.15; the
decision, its rejected alternatives and what it does not cover are
[B-18](../backlog/B-18-cache-versus-realtime.md). Both halves are still unwired — the cache has a
catalogue entry and no module depends on it — so the decision is what the wiring must satisfy rather
than a description of running code.

**Open question 2.** Where does an OTP resend live relative to `Suspend(ttl)`? A resend that does not
extend the TTL will roll a saga back under a subscriber who is still typing. Hypothesis: the resend is
an ordinary HTTP action that does not touch the saga, and the TTL is chosen to outlast two resends.
Check in M1, and record the number that was chosen and why.

**Open question 3.** Is `wasmJs` worth a target? kompot publishes every protocol and client module for
it, so an operator web account would be nearly free. The product is Android and iOS. Deferred — asked
again when the client component set is stable, because the answer changes if any own component turns
out to be platform-bound.

---

## 4. What happens next

The order of work and the acceptance criteria live in [backlog.md](../../backlog.md). The first
things that have to be nailed down, because everything else rests on them:

0. **The build** (`B-01`). Every decision in [research-stack](research-stack.md) is expressed in a
   build file, so nothing below can start first.
1. **The component dictionary** (§1.5, M0). Nine own components with their wire names, in one KSP
   module. Backend-driven UI means the dictionary is the API; renaming a type later is a coordinated
   release of both sides. It is fixed before the first screen, not after the third.
2. **The design system wrapper** (Risk 4, M0). The `resolveSurface` forwarding and the screenshot test
   that guards it — before any theme work, because the defect is invisible once there is enough
   styling to hide in.
3. **The saga skeleton with its outbox assertion** (D6, M1). The wiring test comes before the second
   saga, since it is the thing that stops the silent fallback from being discovered in M4.
