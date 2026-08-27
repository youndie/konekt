package io.konekt.openapi

import io.konekt.RouteGroup
import io.konekt.baseModule
import io.konekt.devOtpRouteGroup
import io.konekt.devScreensRouteGroup
import io.konekt.feature.auth.server.data.JwtConfig
import io.konekt.feature.auth.server.data.RevealedCodes
import io.konekt.feature.auth.server.data.configureAuthentication
import io.konekt.konektRoutes
import io.konekt.mountKonektRoutes
import io.konekt.productionRouteGroups
import io.konekt.theme.BrandThemeCatalogue
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.routing
import io.ktor.server.routing.routingRoot
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The document is a build artefact, so it is committed and compared rather than generated on demand
// — the same arrangement as the wire schemas in :shared:spec, and for the same reason: a diff in a
// pull request is the only place a contract change is ever noticed by a person.
//
// Regenerate on the Mac (a file written on the Linux replica is reverted by mutagen):
//   make openapi
//
// WHAT THIS TEST IS FOR, and it is not the JSON. `openApiDocument` refuses to build at all unless the
// routing tree and `konektEndpointFacts` name exactly the same endpoints, so a route added, removed
// or renamed fails here by name. The comparison against the committed copy is what makes that
// failure reach a reviewer rather than a rerun.
class OpenApiDocumentTest {
    @Test
    fun `the generated document matches what is committed`() {
        val document = documentOf(productionRouteGroups(BrandThemeCatalogue("brand-a")), konektEndpointFacts)

        // Vacuity first. A generator that produced an empty `paths` would satisfy every comparison
        // below, and the committed file could then be empty too and nothing would say so. An exact
        // number rather than a floor: the failure worth catching is a route quietly leaving the
        // surface, and a floor is satisfied by whatever is left.
        val operations =
            document
                .getValue("paths")
                .jsonObject
                .values
                .sumOf { it.jsonObject.size }
        assertEquals(
            EXPECTED_OPERATIONS,
            operations,
            "the server serves $operations endpoints and this test expects $EXPECTED_OPERATIONS — if that " +
                "is a deliberate change, the number here moves with it",
        )

        val rendered = OpenApiFiles.render(document)
        val file = Path(OpenApiFiles.PATH)
        if (OpenApiFiles.recordMode()) file.writeText(rendered)

        assertTrue(
            file.exists(),
            "no committed document at ${OpenApiFiles.PATH} — record one with ${OpenApiFiles.RECORD_ENV}=true",
        )
        assertEquals(
            file.readText(),
            rendered,
            "the committed OpenAPI document has drifted from the routing tree it is generated from. " +
                "Re-record it and read the diff: it is the contract changing.",
        )
    }

    @Test
    fun `the development flag adds exactly one route and nothing else moves`() {
        val production = servedBy(konektRoutes)
        val development = servedBy(konektRoutes + devOtpRouteGroup { RevealedCodes() })

        // The committed document describes a production deployment, so the dev route is absent from
        // it by design. "Absent by design" and "absent because nobody noticed" look identical in a
        // file, which is what this assertion is for: the configuration may add THIS route and no
        // other, and it may take none away.
        assertEquals(
            setOf("GET /api/v1/dev/otp"),
            development - production,
            "DEV_REVEAL_OTP changed the surface by more than the one route the document accounts for",
        )
        assertEquals(
            emptySet(),
            production - development,
            "a route disappears when DEV_REVEAL_OTP is on, which no configuration should be able to do",
        )
    }

    @Test
    fun `the development document builds too, so its description cannot rot`() {
        val document =
            documentOf(
                productionRouteGroups(BrandThemeCatalogue("brand-a")) +
                    devOtpRouteGroup { RevealedCodes() } + devScreensRouteGroup,
                konektEndpointFacts + devOtpEndpointFacts + devScreensEndpointFacts,
            )

        val paths = document.getValue("paths").jsonObject
        // BOTH development routes, named individually. Asserting that the development document is
        // merely larger would pass with one of them missing, and the second one is the newer and
        // therefore likelier to be forgotten.
        assertTrue("/api/v1/dev/otp" in paths, "the development document does not describe the OTP readback")
        assertTrue(
            "/api/v1/dev/screens/forward-compat" in paths,
            "the development document does not describe the forward-compatibility screen",
        )
    }

    @Test
    fun `the way in is public and everything about a subscriber is not`() {
        // The tier is DERIVED from the routing tree rather than declared, so this asserts about the
        // composition and not about a table that agrees with itself. Written as one exact set: a
        // screen route that moved into the public group is the failure, and a per-route assertion
        // list would simply not mention it.
        val public = inventoryOf(konektRoutes).filterNot { it.secured }.map { it.key }.toSet()

        assertEquals(
            setOf(
                "GET /health",
                "POST /api/v1/auth/otp/request",
                "POST /api/v1/auth/otp/verify",
                // THE LOGIN SCREENS AND THEIR SUBMITS, and they must be public for the same reason
                // the two above are: they are the way in, and a screen that gets you a session cannot
                // sit behind one. They add no power — everything they do the OTP pair already did —
                // and what protects all four is the lockout in the use cases plus the fact that no
                // answer depends on whether the number is known.
                "GET /api/v1/screens/login",
                "GET /api/v1/screens/login/code",
                "POST /api/v1/auth/login",
                "POST /api/v1/auth/login/code",
                // Public because the refresh token IS the credential: requiring an access token here
                // would defeat the one thing this endpoint exists for.
                "POST /api/v1/auth/session/refresh",
            ),
            public,
            "the set of endpoints anybody can call is not the set this product decided on",
        )
    }

    private fun documentOf(
        groups: List<RouteGroup>,
        facts: Map<String, EndpointFacts>,
    ): JsonObject {
        var document: JsonObject? = null
        testApplication { document = openApiDocument(routingTreeOf(groups), facts) }
        return document ?: error("the application never assembled")
    }

    private fun inventoryOf(groups: List<RouteGroup>): List<RouteEntry> {
        var inventory: List<RouteEntry>? = null
        testApplication { inventory = routeInventory(routingTreeOf(groups)) }
        return inventory ?: error("the application never assembled")
    }

    private fun servedBy(groups: List<RouteGroup>): Set<String> = inventoryOf(groups).map { it.key }.toSet()

    // The application the document is written from: the composition root MINUS the database, the
    // feature bindings and the workers.
    //
    // That subtraction is what makes a Gradle task possible at all — `Application.module` opens a
    // connection pool in its first line — and it is safe for this purpose because `by inject<T>()` is
    // lazy: a route registers its handler without resolving anything, and nothing here ever serves a
    // request. What it is NOT safe for is anything else, which is why it lives in a test rather than
    // beside the production module.
    private suspend fun ApplicationTestBuilder.routingTreeOf(groups: List<RouteGroup>): RoutingNode {
        var captured: RoutingNode? = null
        application {
            baseModule()
            // The provider has to exist before `authenticate(AUTH_JWT)` is mounted: the route-scoped
            // plugin looks its providers up on install and fails naming the configuration. The secret
            // signs nothing here — no token is ever minted or read.
            configureAuthentication(DESCRIBING_JWT)
            routing { mountKonektRoutes(groups) }
            captured = routingRoot
        }
        startApplication()
        return captured ?: error("the application module never ran")
    }

    private companion object {
        val DESCRIBING_JWT = JwtConfig(secret = "openapi-generator", issuer = "konekt", audience = "konekt-app")

        // The whole product surface plus /health, and without the development routes. The comment
        // above this line said "fifteen" long after the number said 27, which is what a count kept in
        // prose beside a constant does — so it says neither now. What it is for is the change nobody
        // intended: an endpoint appearing or disappearing is a contract moving, and this is the line
        // that makes somebody type the new number and mean it.
        const val EXPECTED_OPERATIONS = 29
    }
}
