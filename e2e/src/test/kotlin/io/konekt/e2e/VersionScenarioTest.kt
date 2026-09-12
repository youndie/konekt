package io.konekt.e2e

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// WHICH BUILD THE IMAGE IS, asked of the image (`konekt#35`).
//
// `ApplicationSmokeTest` already asserts the body's shape, and it cannot assert this: it builds an
// application in-process against whatever `KoreBuildIdentity.kt` the last build left on disk. The
// thing that can go wrong here is one level down — the generated source not reaching the SHIPPED
// distribution — and the only way to see that is to ask a container built from it.
//
// It is the same reason the rest of this suite exists: four defects fatal to the running server once
// survived 191 green tests, because every test below this level builds its own object graph.
class VersionScenarioTest {
    @Test
    fun `the image says which build it is`() =
        runBlocking {
            Stand.client(Stand.serverUrl).use { client ->
                val response = client.get("${Stand.serverUrl}/version")

                assertEquals(HttpStatusCode.OK, response.status, "the image serves no /version")

                val lines =
                    response.bodyAsText().trim().lines().associate {
                        it.substringBefore(":") to
                            it.substringAfter(": ")
                    }

                // THE KEYS AND NOT THE VALUES. A deploy check greps these names; the commit and the
                // build time differ between every build, and a test naming them would be a test of
                // the machine that built the image.
                assertTrue(
                    listOf("release", "version", "commit", "built").all { it in lines },
                    "the /version body is read by deploy checks and lost one of its keys: $lines",
                )

                // The stand sets `KONEKT_RELEASE`, so the override path is the one exercised here —
                // and the compiled-in name is reported beside it rather than silently replaced.
                assertEquals("stand", lines["release"])
                assertTrue(
                    "compiled-release" in lines,
                    "the environment overrode the release and the body did not say so: $lines",
                )

                // NOT ASSERTED: that the commit is a real hash. konekt builds its stand image on a
                // machine whose working tree is replicated WITHOUT `.git`, so the plugin degrades to
                // `unknown` — correctly, and with no way to supply it from outside
                // (youndie/kore#71). A CI-built image carries the real one. Asserting a hash here
                // would be asserting about the build machine.
                assertTrue("commit" in lines, "the body lost the commit line entirely")
            }
        }
}
