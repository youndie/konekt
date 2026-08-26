plugins {
    id("konekt.multiplatform")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.ktor.resources)
            api(libs.kotlinx.serialization.json)
        }
    }
}
