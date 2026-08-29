// Deliberately almost empty. Everything a module needs comes from a convention plugin in
// build-logic, applied by the module itself — `subprojects { }` blocks configure projects from
// outside, which the configuration cache cannot see through and which makes a module's build file
// stop describing that module.
//
// The one thing here is a name for the whole: `./gradlew check` in the root runs every module's
// tests AND its ktlint, so the gate is one command in both places.

plugins {
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.ktlint) apply false
    // AGP, DECLARED HERE THOUGH ONLY `build-logic` AND `:androidApp` APPLY IT.
    //
    // Not decoration: a plugin declared in this block joins the classpath every module's build script
    // shares, and KSP reaches for `AndroidComponentsExtension` the moment an Android plugin is
    // applied anywhere. With AGP only on `build-logic`'s classpath the two are loaded by different
    // classloaders, and configuring ANY task failed with `ClassNotFoundException` naming an AGP class
    // — a message that reads like a corrupt cache and is a classpath split.
    alias(libs.plugins.androidKotlinMultiplatformLibrary) apply false
    alias(libs.plugins.androidApplication) apply false
}
