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
            mavenContent {
                // Both groups on purpose. The portfolio is moving to `io.github.youndie` and
                // sborka is already there — the plugin marker and the jar behind it are under the
                // new one. The old one is held by the library versions published before the move:
                // they are still on the server and resolve as before.
                includeGroupByRegex("io\\.github\\.youndie.*")
                includeGroupByRegex("ru\\.workinprogress.*")
            }
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
