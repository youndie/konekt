// A module both sides speak: the shared domain, and every feature's -shared-api.
//
// JVM, Android, and the three iOS targets. Android joined in `B-85`, which is the item that first
// needed an `.aar`: the multiplatform claim this repository makes is that ONE component registry
// draws the same server-built screens on every platform it names, and until that item the claim was
// compiled on two of the three.
//
// NOTE: nothing here may touch Exposed or MockK. Exposed publishes no common metadata (§1.2) and
// MockK publishes only `common` and `jvm` (§1.3), so both fail at RESOLUTION in a module with an
// iOS target — an error that names a coordinate rather than the rule it broke.

plugins {
    id("konekt.base")
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
    jvmToolchain(libs.findVersion("jvmToolchain").get().requiredVersion.toInt())

    jvm()

    // THE NAMESPACE IS DERIVED FROM THE PATH, not written per module. Android requires one per
    // library and requires them to be distinct; twelve modules each spelling their own is twelve
    // chances to paste a neighbour's, and the failure is a resource merge that names neither.
    // `android { }` and not `androidLibrary { }`: AGP 9.3.1 deprecates the latter and says so at
    // configuration time, and `-Werror` is on in this build.
    android {
        namespace = "io.konekt" + project.path.replace(":", ".").replace("-", "_")
        compileSdk = libs.findVersion("androidCompileSdk").get().requiredVersion.toInt()
        minSdk = libs.findVersion("androidMinSdk").get().requiredVersion.toInt()
    }

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
