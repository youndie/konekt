plugins {
    `kotlin-dsl`
}

// A precompiled script plugin can only `plugins { }` something whose implementation is on THIS
// build's compile classpath. Hence the markers below: they are how a plugin id becomes a
// dependency coordinate.
dependencies {
    implementation(libs.plugins.kotlinJvm.marker())
    implementation(libs.plugins.kotlinMultiplatform.marker())
    implementation(libs.plugins.kotlinSerialization.marker())
    implementation(libs.plugins.ktlint.marker())
    // AGP's multiplatform LIBRARY plugin, because `konekt.multiplatform` applies it: every module
    // both sides speak now has an Android target, and a precompiled script plugin can only name a
    // plugin whose implementation is on this build's compile classpath.
    implementation(libs.plugins.androidKotlinMultiplatformLibrary.marker())
    // AND AGP ITSELF, which the marker does not bring. The marker POM carries the plugin id's
    // implementation and not the Variant API: applying the plugin succeeded and the first task
    // configuration failed with `ClassNotFoundException: AndroidComponentsExtension`, which reads
    // like a corrupt cache rather than a missing dependency.
    implementation(libs.androidGradlePlugin)
    // THE SHARED CONVENTIONS, named the same way. What used to be `konekt.base` — the coordinate,
    // the style, the JUnit platform and the guard that every declared @Test ran — lives in
    // `ru.workinprogress.sborka` now, and the three plugins below are what the conventions in this
    // build are written on top of.
    implementation(libs.plugins.sborkaBase.marker())
    implementation(libs.plugins.sborkaJvm.marker())
    implementation(libs.plugins.sborkaKmp.marker())
    implementation(libs.plugins.sborkaLint.marker())
    implementation(libs.plugins.sborkaTest.marker())
}

// `id:id.gradle.plugin:version` is the artefact the Plugin Portal publishes for every plugin id, and
// resolving it is exactly what `plugins { id(...) }` does behind the scenes.
fun Provider<PluginDependency>.marker(): Provider<String> =
    map { "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version.requiredVersion}" }
