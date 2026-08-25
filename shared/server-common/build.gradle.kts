plugins {
    id("konekt.jvm")
}

dependencies {
    // The tracy agent, so a feature can be handed a trace logger without depending on :server. `api`
    // rather than `implementation`: KonektTrace exposes the agent in its signature, and a feature
    // calling `logger(...)` needs the type.
    api(libs.tracy.agent)

    // The refusals every route can raise, and the error body they become.
    api(project(":shared:domain"))

    // petich's clock adapter lives here for the same reason everything else in this module does: a
    // feature needs it and cannot see :server. That has now happened three times — the clock, the
    // owner check, the money formatter — which is the shape of the rule rather than three accidents.
    api(libs.petich.core)

    api(libs.ktor.server.core)
    // MoneyFormat lives here because it must be somewhere a FEATURE can see and a CLIENT cannot.
    // :shared:domain fails the second half — the client depends on it — and :server fails the first.
    api(libs.ktor.server.auth)
    implementation(libs.ktor.server.statusPages)
    implementation(libs.ktor.serialization.json)
}
