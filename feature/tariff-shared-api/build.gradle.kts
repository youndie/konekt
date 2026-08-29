plugins {
    id("konekt.multiplatform")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // kompot's core, for `ChangeTariffAction`. A feature that puts a verb on the wire needs
            // the action hierarchy it belongs to — the same reason `purchase-shared-api` takes it.
            // `api` on all of them: the `@Resource` classes, the DTOs and the actions stand in this
            // module's public signatures, so a consumer that cannot name them cannot call anything.
            api(project.dependencies.platform(libs.kompot.bom))
            api(libs.kompot.core)
            api(libs.ktor.resources)
            api(libs.kotlinx.serialization.json)
        }
    }
}
