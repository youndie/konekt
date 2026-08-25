plugins {
    id("konekt.jvm")
    alias(libs.plugins.kotlinSerialization)
}

dependencies {
    api(project(":feature:esim-shared-api"))
    api(project(":shared:domain"))

    // The step machine. It is the whole reason the flow is a wizard rather than four endpoints:
    // (session, transition, draft) -> session is a pure function, so the graph is covered by
    // ordinary unit tests with no HTTP, no database and no UI.
    api(platform(libs.kompot.bom))
    api(libs.kompot.wizardCore)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
}
