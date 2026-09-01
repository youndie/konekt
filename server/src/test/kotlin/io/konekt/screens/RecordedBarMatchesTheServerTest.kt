package io.konekt.screens

import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.generated.generatedKonektSerializersModule
import io.github.youndie.kompot.generated.generatedStandardSerializersModule
import io.github.youndie.kompot.kompotCoreSerializersModule
import io.github.youndie.kompot.standard.kompotStandardSerializersModule
import io.konekt.components.BottomNavComponent
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.modules.plus
import kotlin.io.path.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// THE SCREENSHOT FIXTURES ARE RECORDINGS OF THIS SERVER, and nothing held them to it.
//
// `client/src/jvmTest/resources/recorded/*.json` are committed responses that every golden is drawn
// from. They are written by hand, they decode (`RecordedScreenIsRealTest` sees to that), and until
// `B-110` nothing compared them against what the server actually sends — so a wire change reached the
// product and the screenshot suite photographed the old wire, unchanged and green.
//
// That is exactly how it failed: the tabs were given icons, every golden was re-recorded, and NOT ONE
// PIXEL MOVED. The fixtures still carried a bar with no icons, so the suite whose entire job is to
// show what the application looks like was showing what it used to look like.
//
// THE BAR AND NOT THE WHOLE SCREEN, deliberately. A recording of the home screen depends on a
// subscriber, a balance and a clock; holding all of that to the server would mean seeding a database
// here and the guard would be about the fixture generator instead. The bar depends on NOTHING but
// `Shell` — four labels, four deeplinks, four shapes — so it can be compared exactly, and it is the
// piece that appears in every single frame.
class RecordedBarMatchesTheServerTest {
    // THE MODULE THE SERVER SERIALISES WITH, not one assembled here: the recorded file was written
    // by that module, so a comparison made through any other one is a comparison of two encoders.
    private val json =
        Json {
            prettyPrint = false
            classDiscriminator = "type"
            serializersModule =
                kompotCoreSerializersModule +
                kompotStandardSerializersModule +
                generatedStandardSerializersModule +
                generatedKonektSerializersModule
        }

    // The tests run with the module directory as the working directory.
    private val recordings = Path("../client/src/jvmTest/resources/recorded")

    private fun barsIn(text: String): List<JsonObject> {
        val found = mutableListOf<JsonObject>()

        fun walk(node: JsonElement) {
            when (node) {
                is JsonObject -> {
                    if (node["type"]?.jsonPrimitive?.content == BOTTOM_NAV) found += node
                    node.values.forEach(::walk)
                }

                is JsonArray -> {
                    node.forEach(::walk)
                }

                // A string, a number or a boolean has nothing to walk into. Written as an empty
                // block rather than `else -> Unit`: ktlint reformats the latter into a block the
                // compiler then reports as an unused expression under `-Werror`, so the two tools
                // disagree about that one line and this is what both accept.
                else -> {}
            }
        }
        walk(json.parseToJsonElement(text))
        return found
    }

    @Test
    fun `every recorded bar is the bar this server would send`() {
        val files = recordings.listDirectoryEntries("*.json")

        // VACUITY FIRST, and it is not ceremony: this guard reads a directory in ANOTHER MODULE by a
        // relative path. A rename there, or a working directory that is not what this assumes, gives
        // an empty list and a green test about nothing.
        assertTrue(files.isNotEmpty(), "no recordings were found at $recordings; this guard is about nothing")

        val withBars = files.filter { barsIn(it.readText()).isNotEmpty() }
        assertTrue(
            withBars.isNotEmpty(),
            "not one recording carries a `$BOTTOM_NAV`, which cannot be right — the bar is on every " +
                "screen behind a session. Either the type was renamed or the recordings are stale",
        )

        val mismatched =
            withBars.mapNotNull { file ->
                val recorded = barsIn(file.readText()).single()
                val selected =
                    recorded["items"]!!
                        .jsonArray
                        .indexOfFirst { it.jsonObject["selected"]?.jsonPrimitive?.content == "true" }
                val expected = expectedBar(Shell.Tab.entries.getOrElse(selected) { Shell.Tab.HOME })
                if (recorded.toString() == expected) null else file.fileName.toString() to expected
            }

        assertEquals(
            emptyList(),
            mismatched.map { it.first }.sorted(),
            "these recordings carry a bar this server no longer sends, so every golden drawn from " +
                "them photographs an application that no longer exists. Replace the `$BOTTOM_NAV` " +
                "node in each with what the server sends — for the first one that is:\n" +
                (mismatched.firstOrNull()?.second ?: ""),
        )
    }

    private fun expectedBar(selected: Shell.Tab): String =
        json.encodeToString(PolymorphicSerializer(KompotComponent::class), Shell.bottomNav(selected))

    private companion object {
        val BOTTOM_NAV: String =
            BottomNavComponent::class
                .annotations
                .filterIsInstance<SerialName>()
                .single()
                .value
    }
}
