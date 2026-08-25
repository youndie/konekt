plugins {
    id("konekt.jvm")
}

dependencies {
    // The refusals every route can raise, and the error body they become.
    api(project(":shared:domain"))

    api(libs.ktor.server.core)
    api(libs.ktor.server.auth)
    implementation(libs.ktor.server.statusPages)
    implementation(libs.ktor.serialization.json)
}
