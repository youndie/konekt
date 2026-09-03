import org.gradle.api.tasks.PathSensitivity

plugins {
    id("konekt.base")
    id("org.jetbrains.kotlin.multiplatform")
    // The Android LIBRARY half of AGP. This module names its own targets rather than taking
    // `konekt.multiplatform`, so it names this plugin too.
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    // KSP is viddik's requirement rather than a choice of ours: the screenshot cases are GENERATED
    // from `@ViddikScreenshot` and the plugin fails the build outright if the processor is absent.
    alias(libs.plugins.ksp)
    alias(libs.plugins.viddik)
    // The studio's own task (kompot B-20), so that "open the studio" is one command rather than a run
    // configuration each of us keeps in their IDE.
    id("io.github.youndie.kompot.studio") version "0.36.2.125"
}

// The studio runs off the TEST classpath: `BrandFrame`, the recorded responses and the goldens it
// compares against all live there, and a main source set could reach none of them without a second
// copy of the brand composition.
kompotStudio {
    target = "jvm"
    compilation = "test"
}

// NOT `konekt.multiplatform`, and it is still right rather than lazy — for the second reason now
// that the first has gone.
//
// The first was youndie/kompot#84: the toolkit's Compose half published no iOS artefact at all, so a
// Compose client for iOS could not be built on it. **Closed, and released in `0.31.0.76`.** Measured
// at the version this build pins rather than taken from the issue: `kompot-client`,
// `kompot-theme-client` and `kompot-ds-material-compose` each declare `ios_arm64` and
// `ios_simulator_arm64` in their module metadata at `0.32.0.77`.
//
// The second constraint is the one that outlives it, and it is why the convention plugin still does
// not fit: it declares all THREE iOS targets, and the Compose half has two. Compose Multiplatform
// stopped publishing **iosX64** after `1.11.0-alpha01` — the toolkit's own artefacts show the same
// pair, while its protocol half (`kompot-core`, `kompot-realtime`, `kompot-auth`) still ships all
// three. So this module names its targets, and `iosX64()` is not among them.
kotlin {
    jvmToolchain(
        libs.versions.jvmToolchain
            .get()
            .toInt(),
    )

    jvm()

    // ANDROID, AND THE CLAIM IT MAKES TRUE. This module used to say "Android joins with the item that
    // first needs an .aar" — `B-85` is that item, and the reason is not packaging. The claim this
    // repository makes is that ONE component registry draws the same server-built screens wherever
    // the client runs, and it was compiled on two platforms of the three it named.
    //
    // The library half is here and the application is `:androidApp`, for the same reason `:client`
    // has no desktop `main`: a module that draws is not a module that starts.
    android {
        namespace = "io.konekt.client"
        compileSdk =
            libs.versions.androidCompileSdk
                .get()
                .toInt()
        minSdk =
            libs.versions.androidMinSdk
                .get()
                .toInt()

        // HOST TESTS, so `commonTest` actually RUNS on this target rather than merely compiling for
        // it. Without this AGP says so in a warning nobody reads — "the 'commonTest' source directory
        // exists, but android host tests are not enabled" — and the target joins the build with every
        // shared test silently not running on it, which is the Apple gap `AppleTestsAreNotClaimedTest`
        // exists for, arriving by a different door.
        withHostTest {}
    }

    // THE DEVICE TARGET, AND IT NOW PRODUCES SOMETHING. It was declared with no `binaries` block at
    // all, so nothing was ever linked for a phone: every Apple claim in this repository was true of a
    // simulator and of nothing else (`B-90`).
    //
    // THE SAME TWO EXECUTABLES the simulator gets, for the same reasons — one draws and one crashes,
    // and the crash binary must link the reporter and as little else as possible. Linking is the half
    // of `B-90` that needs no hardware, and having it done means the device run is a question of
    // signing and installing rather than of whether the code builds for arm64 at all.
    iosArm64 {
        binaries {
            executable("crash") {
                entryPoint = "io.konekt.client.ios.crashMain"
                baseName = "KonektCrash"
            }
            executable("home") {
                entryPoint = "io.konekt.client.ios.homeMain"
                baseName = "KonektHome"
            }
        }
    }
    iosSimulatorArm64 {
        // AN EXECUTABLE, AND THEREFORE NO XCODE PROJECT. B-27 asks for a deliberate crash in the iOS
        // build to produce a report in katcher, which needs something that runs on a device. The
        // ordinary route is a `.framework` plus an Xcode project linking it — several thousand lines
        // of `.pbxproj` that no Kotlin change can keep correct, for an application whose entire job is
        // to start a reporter and throw.
        //
        // Kotlin/Native emits a Mach-O executable directly, and a simulator `.app` is a directory with
        // an `Info.plist` and a binary in it. `scripts/ios-crash-app.sh` assembles one. What that
        // buys is that the thing which crashes is built by the same compiler, from the same source
        // set, as the reporter it is testing — rather than by a toolchain kept in step by hand.
        binaries {
            // TWO EXECUTABLES AND NOT ONE WITH A SWITCH, because they are two different claims and
            // one of them is about a crash. The harness must link the reporter and as little else as
            // possible — a binary that also carried Compose would make "the reporter runs on a real
            // Apple target" a statement about a much larger program.
            executable("crash") {
                entryPoint = "io.konekt.client.ios.crashMain"
                baseName = "KonektCrash"
            }
            executable("home") {
                entryPoint = "io.konekt.client.ios.homeMain"
                baseName = "KonektHome"
            }
        }
    }

    compilerOptions {
        allWarningsAsErrors.set(true)
    }

    sourceSets {
        commonMain.dependencies {
            // api on the toolkit's client half: KonektDesignSystem implements KompotDesignSystem, so
            // the interface stands in this module's public signature.
            api(project.dependencies.platform(libs.kompot.bom))
            api(libs.kompot.core)
            api(libs.kompot.client)
            // The Material3 design system this one delegates colour and typography to. Named in the
            // constructor, so `api` for the same reason.
            api(libs.kompot.dsMaterialCompose)
            api(libs.kompot.theme)
            api(libs.kompot.themeClient)

            // FORMS, AND THEY ARE A MAIN DEPENDENCY NOW. They were a test-only one, with a comment
            // saying so outright: the form module was there to make the design-system comparison
            // discriminating, "not because this module renders forms yet". B-20 is when it does — the
            // custom package builder is a form, and a client that cannot render one cannot show it.
            //
            // Both halves, because a form is registered twice: `kompot-forms` carries the components
            // and the field definitions the schema is made of, `kompot-forms-client` the renderers
            // that draw them. A client with only one decodes the screen and fails on
            // `$.schema.fields[0]`, which is what the stand suite found the first time it asked for a
            // form.
            api(libs.kompot.forms)
            api(libs.kompot.formsClient)
            // And the two under them: `form-core` is the controller and the patch, `form-standard`
            // the field definitions, values and rules a schema is made of. The components module does
            // not bring them — the split is deliberate upstream, a form's WIRE and a form's LOGIC
            // being separable — so a client that decodes a schema names them itself.
            api(libs.kompot.formCore)
            api(libs.kompot.formStandard)

            api(project(":shared:components"))

            // FOR THE CLOCK, and it is worth saying what this does and does not open up. B-33 makes
            // every timestamp in this build take a `KonektClock`, and a source guard reads the files
            // to keep it that way — it caught the client's tracy agent the hour it was written. There
            // is exactly one definition of that interface, and duplicating it here to avoid a
            // dependency would be a second concept with one name, which is worse than the dependency.
            //
            // It also makes `Money` visible, and that does NOT weaken D15: what the client must not
            // have is a FORMATTER, and `MoneyFormat` lives in `:shared:server-common`, which this
            // module still cannot see.
            api(project(":shared:domain"))
            // The wire this client speaks to, so a path is never written as a string here either.
            api(project(":feature:auth-shared-api"))
            api(project(":feature:realtime-shared-api"))
            api(project(":feature:theme-shared-api"))
            api(project(":feature:usage-shared-api"))
            api(project(":feature:esim-shared-api"))
            // For the plans screen's address and the deeplink that reaches it. The client needs the
            // CONTRACT of the purchase feature and none of its server halves — which is the whole
            // reason a  module exists per feature.
            api(project(":feature:purchase-shared-api"))
            // THE CUSTOM PACKAGE BUILDER'S contract, and its absence is what `B-87` found: the
            // client could not have decoded the form's address or its submit even if a screen had
            // pointed at one.
            api(project(":feature:packages-shared-api"))
            // CHANGING TARIFF, and its absence is what `B-86` found: the server had the saga, the
            // routes and three tariffs, and this module did not depend on the contract at all — so
            // the client could not have decoded the request even if a screen had posted one.
            api(project(":feature:tariff-shared-api"))
            // The shell: the route graph the client resolves deeplinks through, and the
            // action that ends a session.
            api(project(":feature:shell-shared-api"))
            // `kompot-navigation`, and the first use this client has had of it. The graph is a
            // serialisable type the server answers with, so both sides decode the same one.
            api(libs.kompot.navigation)

            // The session lives behind ktor's bearer plugin: it holds the tokens and refreshes them
            // on a 401, which is why `KonektSession` is a store rather than an interceptor.
            api(libs.ktor.client.core)
            implementation(libs.ktor.client.auth)
            implementation(libs.ktor.client.resources)
            implementation(libs.ktor.client.contentNegotiation)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.serialization.json)

            // The realtime channel and its frame contract. The transport is the application's, which
            // is the whole reason this module exists rather than the toolkit shipping one.
            api(libs.kompot.realtime)
            api(libs.kompot.auth)

            // A QR encoder, not a widget: it answers a matrix and the drawing stays ours.
            implementation(libs.qrcode)

            api(libs.compose.runtime)
            api(libs.compose.ui)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            // THE FONTS ARE RESOURCES (`B-114` G1). Manrope and Space Grotesk are compiled into the
            // client on every target through Compose's resource library, which is the one way a
            // font reaches iOS, Android and the desktop from a single declaration. It also makes
            // the goldens platform-independent: with the platform's default face, the same screen
            // rendered on a Mac and on the Linux CI runner differed by 4–8% of its pixels.
            implementation(compose.components.resources)

            // THE TWO AGENTS, AND BOTH MOVED HERE FROM `iosMain`.
            //
            // katcher was Apple-only with a reason: the JVM half of this module was a desktop preview
            // and the crash reporter was genuinely a native concern. B-26's third acceptance criterion
            // ends that — a degradation record leaves a katcher breadcrumb, and a breadcrumb is not a
            // crash. It has to be left wherever a screen degrades, which is every platform.
            //
            // TRACY COULD NOT BE HERE UNTIL TODAY. `agent` published `jvm`, `linux_arm64`, `linux_x64`
            // and `macos_arm64` and no iOS target at all — filed as U11 / youndie/tracy#16 — so
            // structured logging was unavailable on the platform where an out-of-date build is
            // likeliest: a phone updates on the subscriber's schedule, a desktop build on ours.
            // `0.1.13` publishes `ios_arm64`, `ios_simulator_arm64` and `ios_x64`, and the module
            // metadata was read rather than the commit message believed.
            implementation(libs.katcher.client)
            implementation(libs.tracy.agent)
        }

        // The Apple half's own needs: an engine that exists on Apple targets, and Compose's UIKit
        // host so a `@Composable` can be put inside a `UIViewController`.
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }

        // The Android half's own needs, and every line is a platform PROVIDER rather than a feature.
        androidMain.dependencies {
            // An engine that exists on Android. OkHttp rather than CIO because it is the one the
            // platform's own stack is built on, and it carries the TLS the device trusts.
            //
            // `api` and not `implementation`, unlike the Darwin line above, and the difference is
            // where the application lives: the iOS entry point is inside this module and Android's is
            // `:androidApp`, so the engine has to be on ITS compile classpath to be named there.
            api(libs.ktor.client.okhttp)
            // THE MAIN DISPATCHER, exactly as `jvmMain` supplies Swing's. `kotlinx-coroutines-core`
            // declares `Dispatchers.Main` and implements it nowhere; on Android the provider is this
            // artefact, and without it the first code that names `Main` throws rather than being slow.
            implementation(libs.kotlinx.coroutines.android)
            // THE MULTIPLATFORM CLIENT, HERE TOO — and since katcher `0.6.41` it resolves to a real
            // android variant. Before that it resolved `client-jvm`, whose report cache was fixed at
            // `user.dir` — `/` on Android, unwritable — so a crash fired the hook and lost the report at
            // the last step, measured on a device and filed as youndie/katcher#27. `client-android` on
            // its own version line, which declared the same `object Katcher` in the same package and
            // could not share a classpath with this one, is retired upstream: the coordinate now names
            // the android variant of THIS client.
        }

        // The desktop runner's own needs: an engine to talk to the stand with, and Compose's desktop
        // artefacts to open a window at all.
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.ktor.client.cio)
            // THE MAIN DISPATCHER, which nothing else on this classpath provides.
            //
            // `kotlinx-coroutines-core` DECLARES `Dispatchers.Main` and implements it nowhere: the
            // provider arrives through a ServiceLoader from a platform module, and with none present
            // `Main` is not slow or wrong, it throws `MissingMainCoroutineDispatcher` at first touch.
            // Compose Desktop no longer brings one — the window opens on its own EDT dispatcher — so
            // the application is what has to supply it, exactly as an Android build supplies
            // `-android`.
            //
            // The window opening WITHOUT it is what made this invisible: everything drew, and the
            // failure waited for the first piece of code that asked for `Main` by name.
            implementation(libs.kotlinx.coroutines.swing)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        jvmTest.dependencies {
            // Compose UI tests render a real off-screen tree through Skiko, so they need the current
            // OS's runtime rather than only the test framework's API.
            // Reading a component's @SerialName back off the class needs the full reflection
            // runtime; without it the annotation lookup compiles to a warning and answers empty,
            // which would make the coverage guard agree with whatever it found.
            implementation(kotlin("reflect"))
            implementation(compose.desktop.currentOs)
            // A real engine for the session and stream tests, plus MockEngine to drive them without
            // a server. Both: the mock proves the logic, and CIO proves the wiring compiles against
            // an engine that exists.
            implementation(libs.ktor.client.cio)
            implementation(libs.ktor.client.mock)
            // A REAL SERVER for the stream tests. MockEngine and the SSE plugin do not meet: the
            // frames never arrive and the collector simply waits, which is a test measuring the mock.
            // An embedded CIO server on an ephemeral port is the transport itself, and it is the same
            // engine the product runs.
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.cio)
            implementation(libs.ktor.server.sse)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.compose.uiTest)
            // THE SCREEN EDITOR, on the test classpath and not on the client's. Everything it is
            // configured with already lives here — `BrandFrame`, the recorded responses, the goldens
            // it compares against — and a tool on the shipped classpath would be a tool in the app.
            implementation(libs.kompot.studio)
            // THIS BUILD'S OWN VOCABULARY, so the studio lints against the fourteen types konekt
            // ships rather than against the toolkit's seven. Without it every konekt node reads as
            // "a type outside the profile", which is true of the toolkit and false of this client.
            implementation(project(":shared:spec"))
            // AND kompot-spec BESIDE IT, which should not be necessary and is: `:shared:spec` takes
            // kompot-spec as `implementation` while `KonektSpec.generateAll()` hands out a
            // `GeneratedSchema` — a type from it — in its return. A consumer that cannot name the type
            // cannot call the function, and the build stays green either way until somebody tries.
            // The toolkit has a name for this shape (youndie/kompot#70) and an audit against it;
            // konekt has neither yet.
            implementation(libs.kompot.spec)
        }
    }
}

// AGP'S LINT MODEL READS KSP'S OUTPUT AND DOES NOT SAY IT DEPENDS ON IT.
//
// With Android host tests on, `:client:check` fails validation rather than a test:
// `generateAndroidHostTestLintModel` uses `build/generated/ksp/android/androidHostTest/java` "without
// declaring an explicit or implicit dependency". It is a real ordering hazard and Gradle is right to
// refuse — the two tasks would otherwise race, and the losing order produces a lint model built over
// sources that are not there yet.
//
// Declared here rather than turning host tests off, because the alternative is an Android target whose
// shared tests silently do not run, which is the failure the `withHostTest {}` above exists to avoid.
// `matching { }` and not `named(...)`: the task exists only when the Android variant does, and naming
// it directly makes every other module's configuration fail.
// TWO TASKS AND NOT ONE: lint builds a model and then ANALYSES with it, and both read the generated
// directory. The first was the only failure until the first was fixed, which is the ordinary shape of
// this — a missing edge hides the next missing edge.
tasks
    .matching { it.name == "generateAndroidHostTestLintModel" || it.name == "lintAnalyzeAndroidHostTest" }
    .configureEach {
        dependsOn(tasks.matching { it.name == "kspAndroidHostTest" })
    }

// WHERE `-Werror` ACTUALLY APPLIES IN THIS MODULE, WHICH IS NOT WHERE THE BLOCK ABOVE SUGGESTS.
//
// Measured rather than assumed: `allWarningsAsErrors` in `kotlin { compilerOptions { } }` does NOT
// reach the Kotlin/Native platform compilations. `compileKotlinIosArm64` was handed a warning on
// purpose and came back BUILD SUCCESSFUL. It DOES reach the metadata compilations. So for this
// module's Apple sources the flag was enforced in exactly one place — and that place is the one that
// has to give it up.
//
// The iosMain metadata compilation is where every transformed dependency klib lands on one classpath,
// and Compose Multiplatform puts BOTH coordinates of the same library there: ten `unique_name`
// collisions between `org.jetbrains.androidx.lifecycle` and `androidx.lifecycle`,
// `org.jetbrains.compose.runtime` and `androidx.compose.runtime`, and so on down the graph. They are
// the redirected artefacts beside the originals, and no line in this repository changes either.
//
// So the flag MOVES rather than being dropped: off for that one task, and on explicitly everywhere
// else — including the native platform compilations, where it was never on to begin with. Without
// the second half, turning it off in the first would have left this module's iOS sources with no
// `-Werror` at all, which is how a narrow suppression quietly becomes a wide one.
//
// AN ELEVENTH COLLISION WAS OURS. `:client` and `ru.workinprogress.katcher:client` both answered to
// `unique_name=client_commonMain`, because a klib takes its unique name from the module and "client"
// is the most generic name there is — it went unnoticed until a dependency happened to share it.
// `-module-name` on the COMMON metadata compilation is what renames ours; on any other compilation it
// is redundant, because the Kotlin plugin already passes one and the compiler then says so.
//
// `configureEach` and not `named`: these tasks do not exist yet while this file is being evaluated,
// and asking for one by name fails the build with "task not found".
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
    when (name) {
        // The klib whose `unique_name` collided. Only this one: every other compilation is already
        // given a `-module-name` by the Kotlin plugin, and passing a second makes the compiler say
        // "'-module-name' is passed multiple times".
        "compileCommonMainKotlinMetadata" -> compilerOptions.freeCompilerArgs.addAll("-module-name", "konekt-client")

        // The one place the third-party klibs meet, and therefore the one place `-Werror` comes off.
        "compileIosMainKotlinMetadata" -> compilerOptions.allWarningsAsErrors.set(false)

        else -> compilerOptions.allWarningsAsErrors.set(true)
    }
}

// THE DESKTOP RUNNER, and it is a runner rather than the product.
//
// `./gradlew :client:run` opens a window against the stand. That is what B-43's first acceptance
// criterion asks for, and it is the cheapest place where "the application draws what the server sent"
// becomes something a person can look at — the JVM target already renders through Skiko, so nothing
// new is being asked of the toolchain.
//
// It is NOT the shipped application: it signs in through the development endpoint that reads back a
// one-time code, which exists only where `DEV_REVEAL_OTP` is set and must never ship. `Main.kt` says
// so at the top, where somebody copying it will read it.
compose.desktop {
    application {
        mainClass = "io.konekt.client.MainKt"
    }
}

// The screenshot harness. Everything below is a deliberate answer to a way this can go green while
// photographing nothing — see docs/backlog/B-28-screenshot-tests.md.
viddik {
    // `check` runs the comparison. Left at the plugin's default (false) the goldens would only be
    // consulted by somebody who remembered to ask for them, which is the manual review this item
    // exists to replace.
    verifyOnCheck.set(true)
}

// viddik's generated case class is a JUnit 5 `@TestFactory`. In a module wired for JUnit 4 it
// compiles, KSP runs, the file is on disk — and the runner never picks it up, so the suite is green
// and executes NOTHING. This line was here before the plugin was, and it is what covers `jvmTest`;
// `viddikVerify` turns out to pin the platform itself, which is measured in the block below.
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

// THE GOLDENS ARE AN INPUT OF `jvmTest`, AND THEY ARE NOT ONE BY DEFAULT.
//
// `GoldenContentTest` reads the committed PNGs out of `src/jvmTest/snapshots`, which is a resource
// directory of no source set — so Gradle sees no reason to re-run the suite when one changes, and
// the first attempt to prove that guard bites failed for exactly that reason: a golden was renamed,
// `:client:jvmTest` reported UP-TO-DATE, and the stale XML from the previous run said everything
// passed. viddik declares the same directory as an input of its own `viddikVerify`; this does it for
// the ordinary suite, which is where the content assertions live.
tasks.named<Test>("jvmTest") {
    inputs
        .dir(layout.projectDirectory.dir("src/jvmTest/snapshots"))
        .withPropertyName("viddikGoldens")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // AND THE DOCUMENT `RendererCoverageIsDocumentedTest` READS, for exactly the same reason and by
    // the same near-miss: the first attempt to prove that guard bites edited the markdown, ran the
    // suite, and got UP-TO-DATE with the previous run's XML saying everything passed. A file outside
    // the module is not an input of a task that reads it — `AppleTestsAreNotClaimedTest` carries the
    // same warning and has no such declaration, which is worth knowing before trusting it.
    inputs
        .file(layout.settingsDirectory.file("docs/design/design-app-canvas.md"))
        .withPropertyName("canvasDocument")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // The stand-driven suite is not part of an ordinary build: it needs a deployment that is already
    // up, and wired into `check` it would fail every build on a machine that has not started one.
    // The same reasoning `:e2e` carries, and the same answer — a named task, below.
    filter { excludeTestsMatching("io.konekt.client.stand.*") }
}

// THE CLIENT AGAINST A RUNNING DEPLOYMENT, and it is the only place the two halves of this product
// meet with nothing simulated between them.
//
// `:e2e` drives the server over HTTP and asserts about JSON. This drives the CLIENT — the real
// screen source, the real registry, the real holder — against the same stand, and asserts about what
// is on screen. The difference matters for one claim in particular: that the text a subscriber reads
// was composed by the server. A JSON assertion cannot tell a field that reached the screen from one
// that was dropped by a renderer.
val standTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Drives the client against a running stand. Bring it up first: make stand-up"

    val jvmTest = tasks.named<Test>("jvmTest").get()
    testClassesDirs = jvmTest.testClassesDirs
    classpath = jvmTest.classpath
    useJUnitPlatform()

    filter { includeTestsMatching("io.konekt.client.stand.*") }
    // Never up to date: the stand is the input and Gradle cannot see it.
    outputs.upToDateWhen { false }

    systemProperty("konekt.stand.server", System.getenv("KONEKT_STAND_SERVER") ?: "http://127.0.0.1:8080")
}

// THE CASE COUNT, REPORTED — AND WHY THAT IS ALL THIS IS.
//
// `viddikVerify` runs the generated fixtures as JUnit 5 DYNAMIC tests under one class, so a run that
// executed none of them would be indistinguishable from a run that passed all of them: same exit
// code, same silence. That is the failure mode this account has shipped before, and the AC asks for a
// case count, so the count is read back out of the task's own JUnit XML and logged.
//
// **The `check` below has never been observed to fire, and two mutations are why.** With viddik
// 0.1.2.13 the empty run is closed upstream of it:
//
//   * `useJUnit()` on this task does not take — `ViddikPlugin` calls `useJUnitPlatform()` on its own
//     task, so the JUnit-4 trap recorded elsewhere cannot be reproduced here. Measured: the task
//     still compared 8 cases with `useJUnit()` in this file;
//   * `viddik { generateTests = false }` makes the plugin fail the task itself, by name, saying there
//     is nothing to run;
//   * and Gradle's own `failOnNoMatchingTests` fires for the task's `*GeneratedViddikTests*` include
//     when nothing matches it.
//
// So this is a REPORT rather than a guard, and calling it a guard would be the decoration this
// repository is careful about. The guarding is done by `ScreenshotCasesTest`, which names the eight
// cases and both directions of the goldens, and which cannot even COMPILE if KSP generated nothing.
val viddikResults = layout.buildDirectory.dir("test-results/viddikVerify")

tasks.named<Test>("viddikVerify") {
    val resultsDir = viddikResults
    doLast {
        val files =
            resultsDir
                .get()
                .asFile
                .listFiles { file -> file.name.endsWith(".xml") }
                .orEmpty()
        val cases = files.sumOf { file -> Regex("<testcase ").findAll(file.readText()).count() }
        logger.lifecycle("viddikVerify compared $cases screenshot case(s)")
        check(cases > 0) {
            "viddikVerify executed no screenshot cases. The task is green and photographed nothing."
        }
    }
}
