package io.konekt.spec

import io.github.youndie.kompot.spec.SchemaFiles
import io.konekt.components.konektWireNames
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The schema files are the artefact another implementation reads, so they are committed and compared
// rather than generated on demand. The same arrangement as a screenshot golden: generated, pinned,
// and shown in the diff of a pull request — which is the only place a wire change is ever noticed by
// a person.
//
// Regenerate on the Mac (a file written on the Linux replica is reverted by mutagen):
//   LOCAL=1 KONEKT_SPEC_RECORD=true ./gradlew :shared:spec:test
class KonektSchemaGoldenTest {
    @Test
    fun `the generated schemas match what is committed`() {
        val generated = KonektSpec.generateAll()

        // A schema set that generated nothing would pass every comparison below by vacuity, so the
        // count is asserted first and against a floor rather than an exact number: the toolkit adds
        // modules of its own and that must not fail this repository's build.
        assertTrue(generated.size > 1, "the toolkit's modules did not reach the generator")

        // ONLY konekt's own files are committed, and the toolkit's thirteen are not.
        //
        // They come out byte-identical to the ones kompot commits in its own repository, so a copy
        // here is a second source of truth that churns on every kompot bump and produces a diff
        // saying nothing about this product. They are still GENERATED, because the cross-file $ref
        // index is built by walking the modules in order and konekt's file refers to types the
        // toolkit defines — dropping them would change our own schema, not just skip theirs.
        val ours = generated.filter { it.fileName.startsWith("konekt-") }
        // Two: the component dictionary and the eSIM feature's action. An exact number rather than a
        // floor, because the failure worth catching here is a spec module that stopped being
        // assembled — which shrinks this set silently and takes a whole vocabulary off the wire
        // specification while every test about the remaining one still passes.
        assertEquals(2, ours.size, "expected two konekt schema files, got ${ours.map { it.fileName }}")

        if (SchemaFiles.recordMode) {
            ours.forEach { SchemaFiles.write(it.fileName, it.document) }
        }

        ours.forEach { schema ->
            val committed = SchemaFiles.read(schema.fileName)
            assertTrue(
                committed != null,
                "no committed schema for ${schema.fileName} — regenerate with KONEKT_SPEC_RECORD=true",
            )
            assertEquals(
                committed,
                SchemaFiles.render(schema.document),
                "${schema.fileName} has drifted from the types it is generated from",
            )
        }
    }

    @Test
    fun `the profile names every component this build owns`() {
        val components =
            KonektSpec
                .profile()["\$defs"]
                ?.jsonObject
                ?.get("KompotComponent")
                ?.jsonObject
                ?: error("the profile carries no closed KompotComponent hierarchy")

        // Per wire name, not as a count. A single "nine entries" assertion passes on a profile that
        // contains nine of the toolkit's own types and none of konekt's, which is exactly the failure
        // a generated registration produces when it silently generates nothing.
        konektWireNames.forEach { wireName ->
            assertTrue(
                describes(components, wireName),
                "$wireName is missing from this build's profile",
            )
        }
    }

    @Test
    fun `the profile names the action this build owns`() {
        // The same check as above, on the other hierarchy. It is separate because the two are
        // populated by different mechanisms — components by KSP, actions by hand — and a single
        // assertion over both would be satisfied by whichever half still worked.
        val actions =
            KonektSpec
                .profile()["\$defs"]
                ?.jsonObject
                ?.get("KompotAction")
                ?.jsonObject
                ?: error("the profile carries no closed KompotAction hierarchy")

        assertTrue(
            describes(actions, "esim_wizard_step"),
            "esim_wizard_step is missing from this build's profile",
        )
    }

    private fun describes(
        hierarchy: JsonObject,
        wireName: String,
    ): Boolean = hierarchy.toString().contains("\"$wireName\"")
}
