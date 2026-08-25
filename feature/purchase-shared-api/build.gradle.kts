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
        }
    }
}
