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
}

// `id:id.gradle.plugin:version` is the artefact the Plugin Portal publishes for every plugin id, and
// resolving it is exactly what `plugins { id(...) }` does behind the scenes.
fun Provider<PluginDependency>.marker(): Provider<String> =
    map { "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version.requiredVersion}" }
