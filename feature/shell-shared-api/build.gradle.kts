plugins {
    id("konekt.multiplatform")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // api on all of them: the @Resource classes and `SignOutAction` stand in the signatures
            // this module publishes, so a consumer that cannot name them cannot call anything.
            api(project.dependencies.platform(libs.kompot.bom))
            api(libs.kompot.core)
            api(libs.ktor.resources)
            api(libs.kotlinx.serialization.json)
        }
    }
}
