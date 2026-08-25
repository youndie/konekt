import org.jlleitschuh.gradle.ktlint.KtlintExtension

// What every module has regardless of its platform: a coordinate, a version, and one style.
//
// It lives in a convention plugin rather than in `subprojects { }` for two reasons. A module built
// on a lower toolchain than its dependencies fails with a message naming the DEPENDENCY, so the
// toolchain has to be impossible to forget; and a coordinate repeated per module is how six modules
// once got published under a group derived from a directory name.

plugins {
    id("org.jlleitschuh.gradle.ktlint")
}

group = "io.konekt"
version = providers.gradleProperty("VERSION").getOrElse("0.1.0-SNAPSHOT")

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

configure<KtlintExtension> {
    // The FORMATTER's version, pinned from the catalogue rather than left to the plugin's default.
    // Left to the default the style shifts whenever the plugin is bumped — which is precisely the
    // change nobody reads the diff of.
    version.set(libs.findVersion("ktlint").get().requiredVersion)
    // Generated sources are not ours to format, and a formatter that rewrites them makes the
    // generator's next run look like a change.
    filter {
        exclude { it.file.path.contains("/build/generated/") }
    }
}
