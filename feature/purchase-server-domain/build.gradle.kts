plugins {
    id("konekt.jvm")
    alias(libs.plugins.kotlinSerialization)
}

dependencies {
    api(project(":feature:purchase-shared-api"))
    api(project(":shared:domain"))
    // The saga payload and the interceptors are petich types, so they stand in this module's
    // signatures. The engine itself is wired in :server; what lives here is the four steps.
    api(libs.petich.core)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
}
