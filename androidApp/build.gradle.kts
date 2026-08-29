plugins {
    id("konekt.base")
    // NO `org.jetbrains.kotlin.android`. AGP 9 carries Kotlin support itself and REFUSES the plugin
    // outright — "no longer required since AGP 9.0" — which is a good failure and worth recording,
    // because every Android sample written before AGP 9 lists it.
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// THE ANDROID APPLICATION, and it is deliberately the thinnest thing that can be honest.
//
// `:client` is a library and draws; this starts. The split is the same one iOS has — a composition
// root is not an application — and it is what lets `B-85`'s claim be about the registry rather than
// about packaging: everything on screen here is built by the same `KonektComposition` the desktop and
// iOS runners use.
//
// NOT `konekt.multiplatform`, and not multiplatform at all: an application module builds for one
// platform because it IS the platform. `konekt.base` is still here for the group, the version and the
// one style.
android {
    namespace = "io.konekt.android"
    compileSdk =
        libs.versions.androidCompileSdk
            .get()
            .toInt()

    defaultConfig {
        applicationId = "io.konekt.android"
        minSdk =
            libs.versions.androidMinSdk
                .get()
                .toInt()
        targetSdk =
            libs.versions.androidCompileSdk
                .get()
                .toInt()
        versionCode = 1
        versionName = providers.gradleProperty("VERSION").getOrElse("0.1.0-SNAPSHOT")
    }

    // NO SIGNING CONFIG, NO RELEASE BUILD TYPE OF OUR OWN. A debug APK installed by hand is what
    // `B-85` claims and all it claims; a store presence is a non-goal, in `reference-scope`.
    buildFeatures {
        compose = true
    }
}

kotlin {
    jvmToolchain(
        libs.versions.jvmToolchain
            .get()
            .toInt(),
    )
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    implementation(project(":client"))
    // `WindowCompat` comes with it, transitively, and is NOT declared separately on purpose:
    // `androidx.core:core-ktx:1.19.0` refuses to be compiled against anything below API 37, and this
    // build pins 36 deliberately. Naming the artefact would have meant either raising `compileSdk` to
    // the newest API for one helper class, or pinning a second AndroidX version by hand.
    implementation(libs.androidx.activityCompose)
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui)
    // For the window insets: `safeDrawing` and `windowInsetsPadding` live in foundation's layout half.
    implementation(libs.compose.foundation)
}
