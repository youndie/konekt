package io.konekt.screenshots

import io.github.youndie.kompot.studio.KompotStudioConfig
import io.github.youndie.kompot.studio.KompotStudioConfigProvider

// What `./gradlew :client:kompotStudio` opens, and the only thing this build has to write for it.
//
// The plugin's task runs the toolkit's launcher, and the launcher asks the classpath what to open —
// so a provider replaces the `main` that used to sit beside it. Registered in
// META-INF/services beside this file: a ServiceLoader entry is somewhere a reader can find, which a
// class name inside a plugin is not.
class StudioProvider : KompotStudioConfigProvider {
    override val title: String get() = "kompot studio — konekt"

    // The same configuration the pilot's comparison uses, so what somebody opens by hand and what the
    // test asserts are one object rather than two that agree today.
    override fun studioConfig(): KompotStudioConfig = io.konekt.screenshots.studioConfig()
}
