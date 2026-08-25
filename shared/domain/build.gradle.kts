plugins {
    id("konekt.multiplatform")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // The domain types cross the wire inside request and response DTOs, so their serial form
            // is part of the contract and is declared here rather than by whoever transports them.
            api(libs.kotlinx.serialization.json)
        }
    }
}
