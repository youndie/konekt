plugins {
    id("konekt.jvm")
    alias(libs.plugins.kotlinSerialization)
    application
}

application {
    mainClass.set("io.konekt.ApplicationKt")
}

dependencies {
    implementation(project(":shared:domain"))

    // One platform, and no kompot coordinate below names a version. See gradle/libs.versions.toml.
    implementation(platform(libs.kompot.bom))
    implementation(libs.kompot.core)
    implementation(libs.kompot.standard)
    implementation(libs.kompot.ktor)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.resources)
    implementation(libs.ktor.server.contentNegotiation)
    implementation(libs.ktor.server.statusPages)
    implementation(libs.ktor.server.callLogging)
    implementation(libs.ktor.serialization.json)

    implementation(platform(libs.koin.bom))
    implementation(libs.koin.core)
    implementation(libs.koin.ktor)
    implementation(libs.koin.loggerSlf4j)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    runtimeOnly(libs.logback.classic)

    testImplementation(libs.ktor.server.testHost)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
}
