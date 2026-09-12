package io.konekt

import io.github.youndie.kore.generated.KoreBuildIdentity
import io.github.youndie.kore.health.StartupGate
import io.github.youndie.kore.version.releaseOf
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class ApplicationSmokeTest {
    @Test
    fun `the three probes answer three different questions`() =
        testApplication {
            val gate = StartupGate(gates = setOf("migrations"))
            application { baseModule(probes = KonektProbes(startup = gate)) }

            // A gate that is still outstanding, so startup is the only one that refuses. That is the
            // latch, and it is a different condition from being wedged and a different one again from
            // having a dependency that does not answer — which is the whole of konekt#32.
            //
            // NAMED, because `StartupGate()` with no gates starts already open: a service that
            // declares nothing to wait for has nothing to wait for, which is right and would have made
            // this assertion a test of the default rather than of the latch.
            assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/health/startup").status)
            assertEquals(HttpStatusCode.OK, client.get("/health/live").status)
            // No checks registered here, so readiness has nothing to refuse for. A service with no
            // dependency checks is a legitimate shape rather than one that fails closed.
            assertEquals(HttpStatusCode.OK, client.get("/health/ready").status)

            // And the latch opens once, for the thing it was waiting on.
            gate.completed("migrations")
            assertEquals(HttpStatusCode.OK, client.get("/health/startup").status)
        }

    @Test
    fun `health is still there, and it is liveness`() =
        testApplication {
            application { baseModule() }

            // THE ALIAS THE CHART STILL POINTS AT. It must keep answering while the chart moves one
            // line at a time — a rename that breaks a running deployment is not a migration — and it
            // must be the LIVENESS answer rather than the old "ok", which could not fail at all.
            val response = client.get("/health")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("alive", response.bodyAsText().trim())
        }

    // WHICH BUILD THIS IS, asserted on the SHAPE and not on the values. The commit and the build
    // time differ between every run and every machine, so a test naming them would be a test of the
    // machine — and the thing a deploy check depends on is the shape: `key: value` per line, in a
    // fixed order, greppable without a parser.
    @Test
    fun `version answers a contract a shell can read`() =
        testApplication {
            application { baseModule() }

            val body = client.get("/version").bodyAsText()

            assertEquals(
                listOf("release", "version", "commit", "built"),
                body.trim().lines().map { it.substringBefore(":") },
                "the /version body is read by deploy checks, so its keys and their order are a contract",
            )
        }

    // The switch shortens the body and CANNOT remove the route: a 404 is indistinguishable from a
    // broken deployment to the check that reads it. konekt leaves it off — this is a public
    // repository and a commit hash in it is not a secret — so this is the only place it is exercised.
    @Test
    fun `version reduced is the release alone, and is still a route`() =
        testApplication {
            application {
                baseModule(
                    release = releaseOf(KoreBuildIdentity, override = "v9.9.9-for-this-test"),
                    reduced = true,
                )
            }

            val response = client.get("/version")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("release: v9.9.9-for-this-test", response.bodyAsText().trim())
        }

    @Test
    fun `the real application maps a refusal, not just the test one`() =
        testApplication {
            application { baseModule() }

            // ErrorContractTest builds its own application, so it proves the mapping works and not
            // that anything installs it. baseModule is what the real composition root calls, so
            // asserting here is asserting about the thing that ships. A contract written and never
            // installed is the commonest way for one to be absent.
            val response = client.get("/no-such-route")

            assertEquals(HttpStatusCode.NotFound, response.status)
        }
}
