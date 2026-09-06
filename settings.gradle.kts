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

plugins {
    // Where dependencies are looked for — mavenCentral() and google() with their group filters, and
    // the snapshot repository the six toolkits are published to, filtered the same way this file
    // filtered it. A module that declares its own repository resolves against something the rest of
    // the build cannot see, and the difference shows up as a version nobody can explain, so the
    // refusal stays on: `FAIL_ON_PROJECT_REPOS` is the plugin's default.
    //
    // It also checks that this repository's `.editorconfig` is the one the rest of the portfolio
    // uses, which is the other half of pinning the formatter's version.
    id("ru.workinprogress.sborka.settings") version "0.2.0.30"
}

// The Compose Multiplatform client: the design system, the renderers of konekt's own components,
// and nothing else. JVM, Android, and the two iOS targets Compose publishes. Android joined in
// `B-85`, the item that first needed an `.aar` — the multiplatform claim is that ONE registry draws
// the server's screens everywhere, and it was compiled on two platforms of the three it named.
include(":client")

// THE ANDROID APPLICATION, and it is a separate module for the same reason iOS has no Xcode project
// inside `:client`: a module that draws is not a module that starts. It is the thinnest thing that
// can put `KonektComposition` on a screen — one activity that draws and one that crashes.
include(":androidApp")

// The server: Ktor on CIO, the sagas, the mocks, the screens.
include(":server")

// The end-to-end suite. It drives a RUNNING stand over HTTP and is deliberately outside `check`: the
// scenario this build exists to show crosses five processes, and every test below this level can pass
// while the chain is broken at a seam, because each of them owns one end of it.
include(":e2e")

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

// The brand kit's address and the variable that chooses it. A module for one object, for the same
// reason as the one above: the path has to exist once and both halves have to be able to see it.
include(":feature:theme-shared-api")

// The custom package builder's two addresses and its field ids. The ids are here rather than in the
// server because three parties spell them — the schema, the tree and the patch — and the form
// controller keys by string, so a typo is a field that silently never updates.
include(":feature:packages-shared-api")

// The tariff change's addresses and its wire shape. The second saga with a confirmation, so its
// resource tree looks like the purchase's on purpose — the same shape means the same reading.
include(":feature:tariff-shared-api")

// Roaming: a package bought at home that does nothing until it is used abroad. A vertical rather than
// a package, like every other feature — the domain cannot see Exposed, so it cannot depend on it.
// Roaming on the wire, which it had none of until `B-88`: the vertical was server-only, so a client
// could not name the screen that answers "what do I have for this trip".
include(":feature:roaming-shared-api")
include(":feature:roaming-server-domain")
include(":feature:roaming-server-data")
include(":feature:usage-shared-api")
include(":feature:usage-server-domain")
include(":feature:usage-server-data")

// The shell: the route graph and the account screen. A shared-api module with no server half, like
// theme and realtime — it has no domain of its own, and what it holds is what turns a set of screens
// into an application. A bar naming all four features would otherwise have to belong to one of them.
include(":feature:shell-shared-api")

// The wire specification of THIS build: the toolkit's spec modules plus konekt's own, and the
// committed JSON Schema files another implementation would read. JVM-only, because kompot-spec is.
include(":shared:spec")
