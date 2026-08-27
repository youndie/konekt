package io.konekt.conformance

import io.konekt.feature.auth.shared.api.AuthOtp
import io.konekt.feature.auth.shared.api.LoginCodeScreenResource
import io.konekt.feature.auth.shared.api.LoginScreenResource
import io.konekt.feature.packages.shared.api.CustomPackageForm
import io.konekt.feature.packages.shared.api.CustomPackagePatch
import io.konekt.feature.purchase.shared.api.HistoryScreenResource
import io.konekt.feature.purchase.shared.api.OrderScreen
import io.konekt.feature.purchase.shared.api.PlansScreenResource
import io.konekt.feature.purchase.shared.api.Purchases
import io.konekt.feature.purchase.shared.api.TopUps
import io.konekt.feature.shell.shared.api.NavigationResource
import io.konekt.feature.shell.shared.api.ProfileScreenResource
import io.konekt.feature.theme.shared.api.BrandTheme
import io.konekt.feature.usage.shared.api.HomeScreenResource
import io.konekt.openapi.OpenApiFiles
import io.konekt.openapi.endpointKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// THE GATE. It asserts what a conformance walk of this deployment would visit, per check and per
// endpoint, and it asserts it before anything reads a verdict — because a verdict over an empty set
// is not a verdict (B-24).
//
// The subject is the COMMITTED `docs/api/openapi.json`, which is the file `kompot-tck` is handed as
// `TckConfig.openApi`; that the committed copy still matches the routing tree is `OpenApiDocumentTest`'s
// job, and this one would otherwise be measuring a document nobody serves.
class TckCoverageTest {
    @Test
    fun `every check of the kit has something to visit in this deployment`() {
        val endpoints = conformanceEndpoints(committedDocument())

        // Vacuity first, for this test's own reader. Everything below is a statement about a list,
        // and an empty list satisfies a surprising amount of it.
        assertTrue(
            endpoints.isNotEmpty(),
            "no endpoint was read out of ${OpenApiFiles.PATH}; every assertion below would be about nothing",
        )

        assertEveryCheckHasSomethingToVisit(
            tckTargets(endpoints, KONEKT_WALK_PLAN),
            KONEKT_CHECKS_WITH_NOTHING_TO_VISIT,
        )
    }

    @Test
    fun `the endpoints no check will look at are exactly the declared ones`() {
        val skipped = tckSkipped(conformanceEndpoints(committedDocument()), KONEKT_WALK_PLAN)

        assertNothingIsSkippedSilently(skipped, KONEKT_UNWALKED_ENDPOINTS)
    }

    // The per-check counters cannot see this and the kit says so itself: they answer "did this check
    // have targets", and the endpoints that remain keep every check busy while one is left out. So
    // the number is asserted too — a walk that quietly loses a screen keeps every check non-zero.
    @Test
    fun `the walk reaches the number of endpoints this deployment currently lets it reach`() {
        val endpoints = conformanceEndpoints(committedDocument())
        val walked = endpoints.map { it.key }.toSet() - KONEKT_UNWALKED_ENDPOINTS

        assertEquals(
            setOf(
                // The way in. Visited by `authenticate` and claimed by no check — a precondition
                // rather than a check, which is why it is walked and still counts for nothing.
                endpointKey<AuthOtp.Verify>("POST"),
                endpointKey<HistoryScreenResource>("GET"),
                endpointKey<HistoryScreenResource.Page>("GET"),
                endpointKey<HomeScreenResource>("GET"),
                // The catalogue, reachable blind: a public-shaped GET answering one JSON document,
                // and the second screen this build serves that a subscriber can actually get to.
                endpointKey<PlansScreenResource>("GET"),
                // One plan, reachable because a plan id is a catalogue fact rather than something
                // the walk has to create.
                endpointKey<PlansScreenResource.ById>("GET"),
                // The login screens: two forms, public, answering one JSON document each — so a blind
                // walk reaches them without being given anything, and they are the first `form`
                // endpoints it can reach without a session.
                endpointKey<LoginScreenResource>("GET"),
                // Reachable WITHOUT a number, and it answers step one when it has none — which is
                // what makes a blind walk meaningful here rather than a 400 dressed as coverage.
                endpointKey<LoginCodeScreenResource>("GET"),
                // Both addressed by naming an order, and both reachable only because the walk creates
                // one first. The second is the largest component tree this server emits.
                endpointKey<Purchases.ById>("GET"),
                endpointKey<OrderScreen>("GET"),
                // Reachable because the walk tops up before it buys, which is the same reason the
                // stand no longer writes a balance straight into the database.
                endpointKey<TopUps.ById>("GET"),
                // The brand kit: a public GET answering JSON, so a blind walk reaches it without
                // being given anything. It is also the only walked endpoint that is not secured,
                // which is why `auth-required` claims one fewer than the rest.
                "GET ${BrandTheme.PATH}",
                // The custom package form. The first endpoint of kind `form` this build has served,
                // and the reason `form-fields` left the declared-empty list.
                endpointKey<CustomPackageForm>("GET"),
                // Its patch, and the only endpoint here the walk does not reach by a blind GET: it is
                // a POST, and what reaches it is the pairing in `TckWalkPlan.patchEndpoints`. It
                // arrived the day `kompot-tck` grew a kind for it — U13, youndie/kompot#93.
                endpointKey<CustomPackagePatch>("POST"),
                // The account screen, reachable blind: a secured GET answering one JSON document,
                // like the other three tabs.
                endpointKey<ProfileScreenResource>("GET"),
                // THE ROUTE GRAPH, and it is the reason the `navigation` check left the
                // nothing-to-visit list. The walk fetches it, then follows every route in it to the
                // screen behind — which is also how `GET /api/v1/screens/orders` stops being
                // unreachable without anybody adding it here: the graph names it.
                endpointKey<NavigationResource>("GET"),
            ),
            walked,
            "the set of endpoints a conformance walk of this deployment reaches has changed",
        )
    }

    // ---- the gate proved to bite ---------------------------------------------------------------

    @Test
    fun `a route leaving the document makes the check that visited nothing name itself`() {
        val without = committedDocument().withoutPath(historyPagePath())

        val failure =
            assertFailsWith<AssertionError> {
                assertEveryCheckHasSomethingToVisit(
                    tckTargets(conformanceEndpoints(without), KONEKT_WALK_PLAN),
                    KONEKT_CHECKS_WITH_NOTHING_TO_VISIT,
                )
            }

        // The name of the check, not merely a red result: "something is wrong with conformance" is
        // not actionable, and the whole point of the per-check counters is which one went blind.
        assertTrue(
            "pagination" in failure.message.orEmpty(),
            "the gate went red without naming the check that lost its only target: ${failure.message}",
        )
    }

    @Test
    fun `a check declared to have nothing to visit fails once it acquires a target`() {
        val endpoints = conformanceEndpoints(committedDocument())

        val failure =
            assertFailsWith<AssertionError> {
                assertEveryCheckHasSomethingToVisit(
                    tckTargets(endpoints, KONEKT_WALK_PLAN),
                    KONEKT_CHECKS_WITH_NOTHING_TO_VISIT + ("pagination" to "a claim that stopped being true"),
                )
            }

        assertTrue(
            "pagination" in failure.message.orEmpty() && "Delete the entry" in failure.message.orEmpty(),
            "a stale declaration passed unnoticed, which is how an exemption list rots: ${failure.message}",
        )
    }

    @Test
    fun `an endpoint that quietly stops being walked is named`() {
        val endpoints = conformanceEndpoints(committedDocument())
        val skipped = tckSkipped(endpoints, KONEKT_WALK_PLAN)

        val failure =
            assertFailsWith<AssertionError> {
                assertNothingIsSkippedSilently(skipped, KONEKT_UNWALKED_ENDPOINTS - "GET /health")
            }

        assertTrue(
            "GET /health" in failure.message.orEmpty(),
            "the endpoint nobody walks was not named: ${failure.message}",
        )
    }

    // ---- the half that reads a run's own counters ----------------------------------------------

    // No production caller yet: running the kit needs `kompot-tck` on this module's test classpath,
    // which B-24 could not add. Tested here so that the day it is called it is already known to
    // bite, and so that "it compiles" is not the only thing anybody knows about it.
    @Test
    fun `a counter that never appeared is a check that never ran, not a check that passed`() {
        val targets = mapOf("schema" to listOf("GET /a", "GET /b"), "etag" to emptyList())

        val failure =
            assertFailsWith<AssertionError> {
                assertTheWalkVisitedEveryTarget(targets, exercised = mapOf("etag" to 0))
            }

        assertTrue(
            "schema" in failure.message.orEmpty() && "never ran" in failure.message.orEmpty(),
            "an absent counter was read as a pass: ${failure.message}",
        )
    }

    @Test
    fun `a check that visited some of its targets is not a check that visited them`() {
        val targets = mapOf("schema" to listOf("GET /a", "GET /b", "GET /c"))

        val failure =
            assertFailsWith<AssertionError> {
                assertTheWalkVisitedEveryTarget(targets, exercised = mapOf("schema" to 1))
            }

        assertTrue(
            "visited 1 of 3" in failure.message.orEmpty(),
            "a partial walk read as coverage, which is what a floor of one would have done: ${failure.message}",
        )
    }

    @Test
    fun `a run that visited everything on offer passes`() {
        assertTheWalkVisitedEveryTarget(
            targets = mapOf("schema" to listOf("GET /a", "GET /b"), "etag" to emptyList()),
            exercised = mapOf("schema" to 2),
        )
    }

    // ---- helpers -------------------------------------------------------------------------------

    private fun committedDocument(): JsonObject {
        val file = Path(OpenApiFiles.PATH)
        assertTrue(
            file.exists(),
            "no committed document at ${OpenApiFiles.PATH} — record one with `make openapi`, on the Mac",
        )
        return Json.parseToJsonElement(file.readText()).jsonObject
    }

    private fun historyPagePath(): String = endpointKey<HistoryScreenResource.Page>("GET").substringAfter(' ')

    private fun JsonObject.withoutPath(path: String): JsonObject {
        val paths = getValue("paths").jsonObject
        assertTrue(path in paths, "the document does not describe $path, so removing it proves nothing")
        return JsonObject(this + ("paths" to JsonObject(paths - path)))
    }
}
