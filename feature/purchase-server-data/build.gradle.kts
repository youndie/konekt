plugins {
    id("konekt.jvm")
    alias(libs.plugins.kotlinSerialization)
}

dependencies {

    // api: the routing function's signature names the use cases, so whatever installs the routes
    // needs them.
    api(project(":feature:purchase-server-domain"))
    implementation(project(":shared:db"))
    implementation(project(":shared:server-common"))

    implementation(platform(libs.kompot.bom))
    implementation(libs.kompot.core)
    implementation(libs.kompot.standard)
    // THE TOKEN NAMES, and nothing else from a design system: `M3Colors` and `M3Typography` are the
    // words a `text` carries on the wire. Spelling them here as strings would be spelling a
    // vocabulary twice, and the composition root already depends on the same coordinate for the same
    // reason.
    implementation(libs.kompot.dsMaterial)
    implementation(project(":shared:components"))
    // The chrome port, asked for by this feature's own deeplink. A leaf module with no server
    // half that everything may depend on — see `ScreenChrome` for why it goes this way round.
    implementation(project(":feature:shell-shared-api"))
    // The eSIM feature's deeplink, for the door the purchase result now carries. Its wire half only
    // — the same argument as the chrome port above: a leaf module with no server half that anything
    // may depend on, so the purchase feature names a destination without depending on the feature
    // that serves it.
    implementation(project(":feature:esim-shared-api"))
    implementation(libs.kompot.ktor)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.resources)
    implementation(libs.ktor.server.auth)
    implementation(libs.petich.core)
    implementation(libs.petich.postgres)
    implementation(libs.ktor.serialization.json)

    // Exposed publishes no common metadata, which is why every module that touches it is
    // kotlin("jvm"). research-stack §1.2.
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)

    // The platform, again: a BOM constrains only the configuration it is declared in, so a module
    // that names koin-core without it resolves the coordinate with no version at all — the error
    // ends in a bare colon. The same trap as the KSP processor classpath in :shared:components.
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.core)
    implementation(libs.koin.ktor)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.ktor.server.testHost)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(testFixtures(project(":shared:db")))
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.ktor.client.contentNegotiation)
    testImplementation(libs.ktor.server.contentNegotiation)
    testImplementation(libs.ktor.server.resources)
    testImplementation(libs.koin.ktor)
    // Without an slf4j binding the StatusPages logger is a no-op, and an unexpected 500 in a test is
    // a status code with no cause anywhere. That cost twenty minutes once.
    testRuntimeOnly(libs.logback.classic)
    testImplementation(project(":feature:auth-server-data"))
    // The real UsageGrants for the saga tests: a completed purchase grants the plan's
    // allowance, and asserting that against a double would assert the double.
    testImplementation(project(":feature:usage-server-data"))
    testImplementation(libs.mockk)
    testImplementation(testFixtures(project(":feature:roaming-server-domain")))
}
