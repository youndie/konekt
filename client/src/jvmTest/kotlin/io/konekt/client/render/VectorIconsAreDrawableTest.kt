package io.konekt.client.render

import androidx.compose.ui.graphics.vector.PathParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.path.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// PATH DATA THIS CLIENT CANNOT READ DRAWS NOTHING, and that is the exact failure `B-110` rejected the
// name-plus-a-table approach for. Moving the icon onto the wire did not remove it — it moved it: a
// misspelled `d` string is silent in precisely the same way a misspelled icon NAME was.
//
// So it is not left to chance. Every icon this build sends is parsed here and refused if it yields an
// empty path.
//
// THE SUBJECT IS THE RECORDED RESPONSES, which is what makes this a statement about the SERVER rather
// than about four constants copied into a test. `RecordedBarMatchesTheServerTest` holds those
// recordings to what `Shell` actually sends; this reads the same files and asks whether the client
// can draw what is in them. Neither guard is worth much alone and together they close the loop.
class VectorIconsAreDrawableTest {
    private val recordings = Path("src/jvmTest/resources/recorded")

    private fun iconsIn(text: String): List<Pair<String, String>> {
        val found = mutableListOf<Pair<String, String>>()

        fun walk(
            node: JsonElement,
            label: String,
        ) {
            when (node) {
                is JsonObject -> {
                    val here = node["label"]?.jsonPrimitive?.content ?: label
                    node["icon"]?.let { icon ->
                        (icon as? JsonObject)?.get("paths")?.jsonArray?.forEach {
                            found += here to it.jsonPrimitive.content
                        }
                    }
                    node.values.forEach { walk(it, here) }
                }

                is JsonArray -> {
                    node.forEach { walk(it, label) }
                }

                // A string, a number or a boolean has nothing to walk into. Written as an empty
                // block rather than `else -> Unit`: ktlint reformats the latter into a block the
                // compiler then reports as an unused expression under `-Werror`, so the two tools
                // disagree about that one line and this is what both accept.
                else -> {}
            }
        }
        walk(Json.parseToJsonElement(text), "?")
        return found
    }

    @Test
    fun `every icon on the wire parses into something with a shape`() {
        val icons = recordings.listDirectoryEntries("*.json").flatMap { iconsIn(it.readText()) }

        // VACUITY FIRST, and here it is the whole risk. This guard walks JSON looking for a field
        // name; rename `icon` or `paths` on the wire and it finds nothing, passes, and says the icons
        // are fine while the client draws none of them.
        assertTrue(
            icons.isNotEmpty(),
            "no icon was found in any recording under $recordings — either nothing sends one, or the " +
                "field this walks was renamed and this guard is about nothing",
        )

        val broken =
            icons.filter { (_, data) ->
                val path = runCatching { PathParser().parsePathString(data).toPath() }.getOrNull()
                // `isEmpty` rather than a null check: `PathParser` is forgiving, and the failure worth
                // catching is the one that returns a path with nothing in it.
                path == null || path.isEmpty
            }

        assertEquals(
            emptyList(),
            broken.map { "${it.first}: ${it.second}" }.sorted(),
            "these draw nothing at all, which on screen is a tab with no icon — indistinguishable " +
                "from one whose icon has not loaded",
        )
    }
}
