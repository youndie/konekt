plugins {
    id("konekt.base")
    id("org.jetbrains.kotlin.multiplatform")
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// NOT `konekt.multiplatform`, and this is the one module where that is right rather than lazy.
//
// That convention plugin declares the JVM target and all three iOS ones, and **kompot's Compose half
// publishes no iOS artefact at all**: `kompot-client`, `kompot-theme-client` and
// `kompot-ds-material-compose` ship `-android`, `-desktop` and `-wasm-js` and nothing else, while the
// protocol half — `kompot-core`, `kompot-standard` — ships the three iOS targets like everything
// else. The toolkit's README promises otherwise. See research-architecture §1.14 and
// youndie/kompot#84; until that closes, a Compose client for iOS cannot be built on this toolkit.
//
// The second constraint outlives the first: Compose Multiplatform stopped publishing **iosX64** after
// `1.11.0-alpha01`, so even a fixed toolkit reaches two iOS targets and not three. Whatever this
// module grows to, `iosX64()` is not part of it.
kotlin {
    jvmToolchain(
        libs.versions.jvmToolchain
            .get()
            .toInt(),
    )

    // One target today. Android joins with the item that first needs an .aar (B-26/B-27), and iOS
    // when the toolkit can be asked for it.
    jvm()

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

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
