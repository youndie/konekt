plugins {
    id("konekt.jvm")
    alias(libs.plugins.kotlinSerialization)
}

dependencies {
    // api: the routing function's signature names the use cases, so whatever installs the routes
    // needs them.
    api(project(":feature:esim-server-domain"))
    implementation(project(":shared:db"))
    implementation(project(":shared:server-common"))

    implementation(platform(libs.kompot.bom))
    implementation(libs.kompot.core)
    implementation(libs.kompot.standard)
    implementation(project(":shared:components"))
    implementation(libs.kompot.ktor)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.resources)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.serialization.json)

    // Exposed publishes no common metadata, which is why every module that touches it is
    // kotlin("jvm"). research-stack §1.2.
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)

    // The platform again: a BOM constrains only the configuration it is declared in, and a module
    // naming koin-core without it resolves the coordinate with no version at all.
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
    // a status code with no cause anywhere.
    testRuntimeOnly(libs.logback.classic)
    testImplementation(project(":feature:auth-server-data"))
    testImplementation(libs.mockk)
}
