plugins {
    id("konekt.multiplatform")
    alias(wip.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // api, because the @Resource classes stand in the signatures this module publishes.
            api(libs.ktor.resources)
            api(libs.kotlinx.serialization.json)
        }
    }
}
