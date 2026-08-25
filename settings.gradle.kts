@file:Suppress("UnstableApiUsage")

rootProject.name = "konekt"

pluginManagement {
    // The convention plugins. An included build rather than buildSrc: buildSrc invalidates the whole
    // build's configuration cache whenever anything in it changes, and this one holds the toolchain
    // and the style, which are touched more often than that price is worth.
    includeBuild("build-logic")

    repositories {
        gradlePluginPortal()
        // The Exposed Gradle plugin is published to Maven Central and NOT to the Plugin Portal, so
        // without this line it fails with "plugin not found" — which reads like a wrong id.
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    // A module that declares its own repository resolves against something the rest of the build
    // cannot see, and the difference shows up as a version nobody can explain.
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS

    repositories {
        mavenCentral()
        google()

        // The six toolkits. Filtered by group, and that is not decoration: an unfiltered repository
        // takes part in resolving EVERY dependency, and when it is unreachable Gradle disables it
        // and fails everything that had not already resolved — including artefacts that are
        // perfectly fine and come from somewhere else entirely.
        maven("https://reposilite.kotlin.website/snapshots") {
            name = "wip-snapshots"
            mavenContent {
                includeGroup("io.github.youndie")
                includeGroup("io.github.youndie.booblik")
                includeGroup("ru.workinprogress")
                includeGroupByRegex("ru\\.workinprogress\\..*")
            }
        }
    }
}

// The server: Ktor on CIO, the sagas, the mocks, the screens.
include(":server")

// The domain shared by both sides — Money first. Multiplatform, because the client renders types it
// must be able to name; JVM plus the three iOS targets, with Android joining when the client module
// does.
include(":shared:domain")
