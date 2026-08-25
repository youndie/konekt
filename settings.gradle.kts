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

        // viddik's Gradle plugin is published here and nowhere else. Filtered like every
        // third-party repository in this file: an unfiltered one takes part in resolving EVERY
        // plugin, and when it is unreachable Gradle disables it and fails plugins it never served.
        maven("https://reposilite.kotlin.website/snapshots") {
            name = "wip-snapshots"
            content { includeGroupByRegex("ru\\.workinprogress.*") }
        }
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

// The Compose Multiplatform client: the design system, the renderers of konekt's own components,
// and nothing else. JVM plus the three iOS targets; ANDROID IS NOT HERE YET, deliberately — the
// convention plugin says it joins with "the item that first needs an .aar", and drawing a design
// system and diffing two renders of it does not. B-26/B-27 do.
include(":client")

// The server: Ktor on CIO, the sagas, the mocks, the screens.
include(":server")

// The domain shared by both sides — Money first. Multiplatform, because the client renders types it
// must be able to name; JVM plus the three iOS targets, with Android joining when the client module
// does.
include(":shared:domain")

// The component dictionary: the nine wire types this product owns, in one KSP module. In a
// backend-driven product the dictionary IS the API, which is why it is fixed before the first screen
// rather than grown one screen at a time.
include(":shared:components")

// The Exposed declarations of the tables no single feature owns — subscriber and account. In a
// module of its own because more than one feature reads them, and a second declaration of one table
// is two schemas that agree until they do not.
include(":shared:db")

// Server-side code every feature shares: who is acting, the owner check, the mapping from a refusal
// to a status, and the money formatter. In a module rather than in :server because a feature cannot
// depend on the thing that composes it — which is the constraint that put each of these here, one at
// a time. Not in :shared:domain either: the client depends on that, and the point of the formatter
// is that a client cannot reach it.
// NOT `:shared:server`. Gradle allows two projects with the same simple NAME in one build — the
// paths differ — and the Kotlin plugin then resolved a project dependency to the wrong one, which
// surfaced as a circular dependency between `:server:compileKotlin` and `:server:jar`: an error
// naming neither the collision nor this module.
include(":shared:server-common")

// The first feature vertical. Four modules rather than a package, because the layering is then the
// compiler's business: -server-domain cannot see Exposed, so it cannot accidentally depend on it,
// which is the entire reason the repository interface exists. See research-stack D12.
include(":feature:auth-shared-api")
include(":feature:auth-server-domain")
include(":feature:auth-server-data")

// The second feature vertical: buying a package, which is where petich earns its place. Four
// interceptors, one of them a wait for a human, and a compensated branch the canvas draws.
include(":feature:purchase-shared-api")
include(":feature:purchase-server-domain")
include(":feature:purchase-server-data")

// Counters: what a subscriber has left. Two modules rather than four — nothing here crosses the wire
// as a DTO, because a counter reaches the client as a COMPONENT and not as data.
// The eSIM order wizard. The step machine is wizard-core's; the chrome is konekt's own step_meter,
// because kompot-wizard's WizardScreenComponent presupposes a FormSchema this flow does not have.
// See docs/research/research-architecture.md §1.12.
include(":feature:esim-shared-api")
include(":feature:esim-server-domain")
include(":feature:esim-server-data")

// The home screen's path. A shared-api with no server-domain beside it, because the screen it names
// is assembled in the composition root out of two features rather than owned by one.
// The update stream's path and topic. A module for one object, because SSE takes a plain string on
// both sides and the rule that a path exists once has to be kept somewhere both can see.
include(":feature:realtime-shared-api")
include(":feature:usage-shared-api")
include(":feature:usage-server-domain")
include(":feature:usage-server-data")

// The wire specification of THIS build: the toolkit's spec modules plus konekt's own, and the
// committed JSON Schema files another implementation would read. JVM-only, because kompot-spec is.
include(":shared:spec")
