plugins {
    id("konekt.jvm")
    alias(wip.plugins.kotlinSerialization)
}

dependencies {
    api(project(":feature:purchase-shared-api"))
    api(project(":shared:domain"))
    // The usage feature's ports stand in Provision's constructor: a completed purchase
    // grants an allowance, and what an allowance is made of belongs to that domain rather than this
    // one. A feature depending on a feature, which the layering allows — what it forbids is a
    // feature depending on `:server`.
    api(project(":feature:usage-server-domain"))
    // Roaming's ports, for the one branch in provisioning that differs. `api` rather than
    // `implementation` because Zones and the payload's zone are part of what this module exposes.
    api(project(":feature:roaming-server-domain"))
    // The saga payloads and the members are petich types, so they stand in this module's
    // signatures. The engine itself is wired in :server; what lives here is the purchase and top-up
    // definitions and their members.
    api(libs.petich.core)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
}
