plugins {
    id("konekt.multiplatform")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // The @Resource class stands in this module's public signature, so a consumer that cannot
            // name it cannot call anything. No kompot dependency: roaming puts no ACTION on the wire —
            // its screen is read and its packages are started by the network, not by a press.
            api(libs.ktor.resources)
            api(libs.kotlinx.serialization.json)
        }
    }
}
