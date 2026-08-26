plugins {
    id("konekt.jvm")
    alias(libs.plugins.kotlinSerialization)
}

dependencies {
    api(project(":feature:purchase-shared-api"))
    api(project(":shared:domain"))
    // The usage feature's ports stand in ProvisionInterceptor's constructor: a completed purchase
    // grants an allowance, and what an allowance is made of belongs to that domain rather than this
    // one. A feature depending on a feature, which the layering allows — what it forbids is a
    // feature depending on `:server`.
    api(project(":feature:usage-server-domain"))
    // Roaming's ports, for the one branch in provisioning that differs. `api` rather than
    // `implementation` because Zones and the payload's zone are part of what this module exposes.
    api(project(":feature:roaming-server-domain"))
    // The saga payload and the interceptors are petich types, so they stand in this module's
    // signatures. The engine itself is wired in :server; what lives here is the four steps.
    api(libs.petich.core)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
}
