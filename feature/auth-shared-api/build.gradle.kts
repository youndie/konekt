plugins {
    id("konekt.multiplatform")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // api on both: the @Resource classes and the DTOs stand in the signatures this module
            // publishes, so a consumer that cannot name them cannot call anything.
            api(libs.ktor.resources)
            api(libs.kotlinx.serialization.json)
            // AND KOMPOT, since this module gained an ACTION. Every other feature's wire module
            // already declares it for the same reason — `purchase-shared-api` for `buy_plan`,
            // `shell-shared-api` for `sign_out` — and auth was the odd one out only because signing
            // in had no verb of its own: the two steps are forms, and the toolkit's own
            // `update_session` is what comes back.
            api(project.dependencies.platform(libs.kompot.bom))
            api(libs.kompot.core)
        }
    }
}
