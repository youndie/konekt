plugins {
    id("konekt.multiplatform")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonTest.dependencies {
            // Cancellation is the whole point of suspendRunCatching, and proving it needs a real
            // coroutine to cancel rather than a thrown exception to catch.
            implementation(libs.kotlinx.coroutines.test)
        }
        commonMain.dependencies {
            // The domain types cross the wire inside request and response DTOs, so their serial form
            // is part of the contract and is declared here rather than by whoever transports them.
            api(libs.kotlinx.serialization.json)
        }
    }
}
