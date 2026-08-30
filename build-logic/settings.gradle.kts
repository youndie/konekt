@file:Suppress("UnstableApiUsage")

rootProject.name = "build-logic"

dependencyResolutionManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()

        // The shared conventions this build's plugins are built on. Filtered like every third-party
        // repository here: an unfiltered one takes part in resolving EVERY coordinate, and when it is
        // unreachable Gradle disables it and fails artefacts it never served.
        maven("https://reposilite.kotlin.website/snapshots") {
            name = "wip-snapshots"
            mavenContent { includeGroupByRegex("ru\\.workinprogress.*") }
        }
    }

    // The same catalogue the main build uses, so a version exists in exactly one file. Without this
    // the convention plugins would carry their own copies of the Kotlin and ktlint versions, and the
    // two would drift — silently, because each half compiles.
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}
