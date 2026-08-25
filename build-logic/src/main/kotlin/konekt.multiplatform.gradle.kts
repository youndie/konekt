// A module both sides speak: the shared domain, and every feature's -shared-api.
//
// JVM plus the three iOS targets. Android joins when the client module does — the SDK is present on
// both the Mac and the Linux box, so it is a decision rather than an obstacle, and it belongs to the
// item that first needs an .aar.
//
// NOTE: nothing here may touch Exposed or MockK. Exposed publishes no common metadata (§1.2) and
// MockK publishes only `common` and `jvm` (§1.3), so both fail at RESOLUTION in a module with an
// iOS target — an error that names a coordinate rather than the rule it broke.

plugins {
    id("konekt.base")
    id("org.jetbrains.kotlin.multiplatform")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
    jvmToolchain(libs.findVersion("jvmToolchain").get().requiredVersion.toInt())

    jvm()
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    compilerOptions {
        allWarningsAsErrors.set(true)
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
