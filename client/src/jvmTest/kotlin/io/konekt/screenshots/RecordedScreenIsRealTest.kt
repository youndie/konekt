package io.konekt.screenshots

import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.UnknownComponent
import io.github.youndie.kompot.decodeKompotComponent
import io.github.youndie.kompot.standard.ColumnComponent
import io.github.youndie.kompot.standard.RowComponent
import io.github.youndie.kompot.standard.TextComponent
import io.konekt.client.net.konektClientJson
import io.konekt.components.SurfaceComponent
import io.konekt.components.UsageCounterCardComponent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// WHAT MAKES THE RECORDING WORTH COMMITTING, as opposed to a file that happens to parse.
//
// `GoldenContentTest` already refuses a blank frame, and a blank frame is not the failure to fear
// here: a recording that decodes into two unknown-component blocks would draw a perfectly good
// picture of the degradation and be filed as the home screen. So this asserts what the recording IS.
class RecordedScreenIsRealTest {
    private val tree: KompotComponent =
        konektClientJson.decodeKompotComponent(
            checkNotNull(javaClass.getResourceAsStream("/recorded/home-screen.json")) {
                "the recorded response is missing"
            }.bufferedReader().use { it.readText() },
        )

    // EVERY CONTAINER, and the list is exhaustive by hand because there is no `children` on
    // `KompotComponent` — nesting is a convention of each type rather than a property of the
    // interface. That makes this exactly the kind of list that goes stale without failing, and it
    // did: the day the balance became a `surface`, this walk stopped seeing the amount and reported
    // "no formatted amount in the recording" about a recording that had one. A missing container
    // here does not break the test, it makes it look at less — which is the worse failure.
    private fun KompotComponent.walk(): List<KompotComponent> =
        listOf(this) +
            when (this) {
                is ColumnComponent -> children.flatMap { it.walk() }
                is RowComponent -> children.flatMap { it.walk() }
                is SurfaceComponent -> children.flatMap { it.walk() }
                else -> emptyList()
            }

    @Test
    fun `this build can draw every component the server sent`() {
        val unknown = tree.walk().filterIsInstance<UnknownComponent>()

        // Not "the golden is not blank" — a screen of two degradation blocks is not blank either.
        assertEquals(
            emptyList(),
            unknown.map { it.originalType },
            "the recorded home screen carries types this build cannot draw, so its golden photographs " +
                "the degradation and files it as the home screen",
        )
    }

    @Test
    fun `it carries text only the server could have composed`() {
        val texts =
            tree.walk().mapNotNull {
                when (it) {
                    is TextComponent -> it.text
                    is UsageCounterCardComponent -> it.valueText
                    else -> null
                }
            }

        // D15: the client owns no formatter for money and none for gigabytes. A formatted amount and
        // a formatted allowance on this screen can only have been given to it — which is the whole
        // claim a golden of a SERVER-produced tree exists to keep true.
        assertTrue(
            texts.any { it.startsWith("$") },
            "no formatted amount in the recording, so it cannot show that the server formatted it: $texts",
        )
        assertTrue(
            texts.any { it.endsWith("GB left") || it.endsWith("MB left") },
            "no formatted allowance in the recording: $texts",
        )
    }
}
