import org.gradle.api.tasks.PathSensitivity

plugins {
    id("konekt.base")
    id("org.jetbrains.kotlin.multiplatform")
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    // KSP is viddik's requirement rather than a choice of ours: the screenshot cases are GENERATED
    // from `@ViddikScreenshot` and the plugin fails the build outright if the processor is absent.
    alias(libs.plugins.ksp)
    alias(libs.plugins.viddik)
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

    // Android joins with the item that first needs an .aar.
    jvm()
    iosArm64()
    iosSimulatorArm64()

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

            api(project(":shared:components"))
            // The wire this client speaks to, so a path is never written as a string here either.
            api(project(":feature:auth-shared-api"))
            api(project(":feature:realtime-shared-api"))
            api(project(":feature:usage-shared-api"))
            api(project(":feature:esim-shared-api"))

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
        }

        // THE APPLE HALF'S CRASH REPORTER. `iosMain` rather than `commonMain` because the JVM half of
        // this module is a desktop preview and the server has its own reporter to come (B-26) — and
        // because katcher publishes the platform hook as an `actual` in its `nativeMain`, so what is
        // wired here is genuinely a native concern.
        iosMain.dependencies {
            implementation(libs.katcher.client)
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

            // A TEST dependency and not a main one, and the reason is the whole point of the fixture
            // it serves. Only three renderers in the toolkit read the surface hook — button,
            // text_input and read_only_field — and of those, only the two form ones answer
            // differently from Material's default under konekt's brand: a Material button is already
            // a pill, so a button alone cannot tell a design system that answers from one that does
            // not. The form module is here to make the comparison discriminating, not because this
            // module renders forms yet.
            implementation(libs.kompot.forms)
            implementation(libs.kompot.formsClient)
        }
    }
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
