package io.konekt.components

import io.github.youndie.kompot.encodeKompotComponent
import io.github.youndie.kompot.standard.ColumnComponent
import io.github.youndie.kompot.standard.PaginatedListComponent
import io.github.youndie.kompot.standard.RowComponent
import io.github.youndie.kompot.standard.TextComponent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// WHAT STOPS `konektWalk` GOING STALE, and it needed something: the walk is a hand-kept list of which
// types nest, and the failure mode of such a list is not an error. It is a walk that looks at LESS
// and reports an absence, which lands the accusation on the product — four separate copies of this
// list did exactly that, twice.
//
// THE JSON IS THE ORACLE. A serialized tree cannot hide a nesting: a component is an object with an
// `id`, wherever it sits, and no list of container types is involved in finding one. So the assertion
// is that the typed walk reaches the same ids the JSON does — and a container added without a `when`
// branch fails HERE, beside the walk, instead of somewhere downstream saying a screen is missing text.
//
// It is not a second implementation kept for its own sake. The JSON walk cannot replace the typed one
// — callers assert on `TextComponent.text` and on `UnknownComponent.originalType`, which are facts
// about decoded objects — and the typed one cannot check itself.
class WalkCoversEveryContainerTest {
    // Every konekt component, put inside every container this build can send, nested two deep so a
    // walk that descends exactly one level is not accidentally right.
    private fun treeOfEverything(): ColumnComponent {
        val leaves = konektDictionary.map { it.second }

        return ColumnComponent(
            id = "screen",
            spacing = 12,
            children =
                listOf(
                    RowComponent(id = "in-a-row", spacing = 8, children = leaves),
                    SurfaceComponent(id = "in-a-surface", children = leaves),
                    PaginatedListComponent(
                        id = "in-a-list",
                        initialItems = leaves,
                        // THE FIELD THREE OF THE FOUR OLD COPIES NEVER FOLLOWED. It is the node drawn
                        // when the list is empty — which is exactly when it is the only thing on the
                        // screen, so a walk that skips it is blind at the moment there is one thing
                        // to see.
                        emptyState = TextComponent(id = "in-an-empty-state", text = "Nothing here yet."),
                    ),
                    // A container inside a container, because a walk can descend one level by
                    // accident and cannot descend two.
                    SurfaceComponent(
                        id = "nested-outer",
                        children = listOf(ColumnComponent(id = "nested-inner", spacing = 4, children = leaves)),
                    ),
                ),
        )
    }

    @Test
    fun `the walk reaches every component the wire carries`() {
        val tree = treeOfEverything()

        val walked = tree.konektWalk().map { it.id }.toSet()
        val serialized = idsIn(konektTestJson.parseToJsonElement(konektTestJson.encodeKompotComponent(tree)))

        // Vacuity first: an oracle that found nothing would agree with a walk that found nothing.
        assertTrue(serialized.size > konektDictionary.size, "the JSON walk found $serialized — it is not looking")

        assertEquals(
            serialized,
            walked,
            "the walk did not reach every node of the tree. A component with children was added to " +
                "the dictionary and `konektWalk` was not told about it — so every test that walks a " +
                "screen now looks at less and will report the contents as ABSENT rather than fail here",
        )
    }

    // Every component in the encoded tree, found the one way that cannot go stale: an `id`.
    //
    // `id` AND NOT `type`, which was the first attempt and is wrong: an action carries a `type` and
    // so does a modifier, and neither is a component — so keying on it would demand the typed walk
    // descend into things that are not nodes. Every `KompotComponent` declares `id`; nothing else in
    // a tree does.
    private fun idsIn(node: JsonElement): Set<String> =
        buildSet {
            if (node is JsonObject) {
                node["id"]?.jsonPrimitive?.contentOrNull?.let(::add)
                node.values.forEach { addAll(idsIn(it)) }
            }
            if (node is JsonArray) {
                node.forEach { addAll(idsIn(it)) }
            }
        }
}
