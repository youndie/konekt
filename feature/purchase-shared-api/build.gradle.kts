plugins {
    id("konekt.multiplatform")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // api on both: the @Resource classes and the DTOs stand in the signatures this module
            // publishes, so a consumer that cannot name them cannot call anything.
            // kompot's core, for `BuyPlanAction`. A feature that puts a verb on the wire needs the
            // action hierarchy it belongs to — the same reason `esim-shared-api` takes it.
            api(project.dependencies.platform(libs.kompot.bom))
            api(libs.kompot.core)
            api(libs.ktor.resources)
            api(libs.kotlinx.serialization.json)
        }
    }
}
