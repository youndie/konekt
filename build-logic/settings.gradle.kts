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
                // One group, and it is the only one there can be. The portfolio's move to
                // `io.github.youndie` is finished: nothing this build resolves is under
                // `ru.workinprogress` any more, and a filter naming a group the server is never asked
                // about reads as a dependency that is still there.
                includeGroupByRegex("io\\.github\\.youndie.*")
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
