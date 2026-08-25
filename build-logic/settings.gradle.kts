@file:Suppress("UnstableApiUsage")

rootProject.name = "build-logic"

dependencyResolutionManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
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
