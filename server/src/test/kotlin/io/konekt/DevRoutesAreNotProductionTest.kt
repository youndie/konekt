package io.konekt

import io.konekt.feature.auth.server.data.RevealedCodes
import io.konekt.openapi.inventoryOf
import io.konekt.testing.productionSources
import io.konekt.theme.BrandThemeCatalogue
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// THE GUARD FOUR FILES ALREADY NAMED.
//
// Three development routes and one client test carried the comment `… with
// `DevRoutesAreNotProductionTest` keeping it off a real build`, and no such test existed (`B-84`).
// What actually held the line was one assertion inside `ForwardCompatScreenTest` — object identity,
// one group, in a test named after a screen — and `devOtpRouteGroup` had no equivalent at all.
//
// The routes are worth a guard rather than a deletion. `/api/v1/dev/fail` answers 500 by
// construction, which is a denial-of-service primitive if it ships; `/api/v1/dev/roaming/arrive` is
// public and takes `subscriberId` from the QUERY rather than from the token, so wherever it is
// enabled anyone can start a stranger's roaming package and spend their allowance.
//
// WHY THE ROUTE TABLE AND NOT THE FLAGS. A test over `DEV_SCREENS` and `DEV_REVEAL_OTP` proves the
// default is off, which is a fact about one configuration file. What matters is that no development
// route is reachable in the composition every deployment mounts, however the flags are read — and
// that composition has a name: `productionRouteGroups`.
class DevRoutesAreNotProductionTest {
    @Test
    fun `no development route is in the production route table`() {
        val offenders = inventoryOf(productionRouteGroups(CATALOGUE)).map { it.key }.filter(::isDevelopment)

        assertEquals(
            emptyList(),
            offenders,
            "a development route is mounted by every deployment: $offenders",
        )
    }

    // THE POSITIVE CONTROL, and without it the assertion above is a test of a detector that finds
    // nothing. It also fixes what the detector recognises: both groups, named individually, because
    // asserting that the development table is merely larger would pass with either one missing.
    @Test
    fun `the detector finds both development groups when they are mounted`() {
        val everything =
            inventoryOf(
                productionRouteGroups(CATALOGUE) + devScreensRouteGroup + devOtpRouteGroup { RevealedCodes() },
            ).map { it.key }.filter(::isDevelopment).toSet()

        assertEquals(
            setOf(
                "GET /api/v1/dev/screens/forward-compat",
                "POST /api/v1/dev/roaming/arrive",
                "GET /api/v1/dev/fail",
                "GET /api/v1/dev/otp",
            ),
            everything,
            "the development routes this guard knows about are not the ones the development table mounts",
        )
    }

    // WHAT KEEPS THE DETECTOR FROM GOING BLIND. The check above reads a PATH, so a development route
    // whose path did not say `dev` would be mounted into production and greeted with silence. This
    // one reads the source instead: every `@Resource` declared under a `dev` package spells its path
    // under `/api/v1/dev/`, so the first check can see it.
    //
    // A source scan rather than reflection because the failure is a route that was never added to any
    // table — there is nothing to reflect over until somebody mounts it, and by then it is too late.
    @Test
    fun `every resource in a dev package spells a dev path`() {
        val declarations =
            productionSources()
                .filter { it.parent.fileName.toString() == "dev" }
                .flatMap { file -> RESOURCE_PATH.findAll(file.readText()).map { file.fileName to it.groupValues[1] } }

        // Vacuity: a walk that found no files at all would satisfy every assertion below. The three
        // are `FailingRouting`, `ForwardCompatScreen` and `ArriveRouting`; the number is here so that
        // a file moving out of a `dev` package is noticed rather than silently dropping a subject.
        //
        // `productionSources()` walks every module rather than this one, so a development route added
        // in a feature module is in scope — which is where the next one would most plausibly land.
        assertEquals(
            3,
            declarations.size,
            "the sources under a `dev` package declare a different number of resources: $declarations",
        )
        declarations.forEach { (file, path) ->
            assertTrue(
                path.startsWith(DEV_PREFIX),
                "$file declares `$path`, which is under a `dev` package and outside `$DEV_PREFIX` — " +
                    "the route-table guard reads paths, so it cannot see this one",
            )
        }
    }

    private fun isDevelopment(key: String): Boolean = DEV_PREFIX in key

    private companion object {
        val CATALOGUE = BrandThemeCatalogue("brand-a")

        // The segment, with its slashes, so `/api/v1/development-plans` could never match it.
        const val DEV_PREFIX = "/api/v1/dev/"

        // `@Resource("/api/v1/dev/fail")` — the annotation as it is written, single argument, string
        // literal. Every dev resource in this build is declared that way; one that was not would show
        // up as a change in the count above rather than as a pass.
        //
        // The dev OTP resource is NOT in this scan: it is declared in `feature/auth-shared-api`, which
        // is not a `dev` package, because the client needs the same constant to call it against a
        // stand. The route-table check covers it, and the positive control names it.
        val RESOURCE_PATH = Regex("""@Resource\("([^"]+)"\)""")
    }
}
