plugins {
    id("konekt.multiplatform")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // api on both: the @Resource classes stand in the signatures this module publishes.
            api(libs.ktor.resources)
            api(libs.kotlinx.serialization.json)
        }
    }
}
