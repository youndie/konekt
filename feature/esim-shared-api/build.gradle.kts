plugins {
    id("konekt.multiplatform")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // api on all four: the @Resource classes, the DTOs and the wizard action stand in the
            // signatures this module publishes, so a consumer that cannot name them cannot call
            // anything.
            api(libs.ktor.resources)
            api(libs.kotlinx.serialization.json)

            // `project.dependencies.platform(...)`, not a bare `platform(...)`: inside a
            // source-set dependency block the receiver is KotlinDependencyHandler, which has no
            // `platform` of its own, and the error names the function rather than the receiver.
            api(project.dependencies.platform(libs.kompot.bom))
            // KompotAction, for the one action this feature adds to the wire.
            api(libs.kompot.core)
            // WizardTransition travels on that action. It is wizard-core's type rather than a
            // vocabulary of ours because inventing a second word for "next" would leave two
            // spellings of one idea, and this one already has a @SerialName chosen for the wire.
            api(libs.kompot.wizardCore)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
