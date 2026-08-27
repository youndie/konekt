package io.konekt.wire

import io.github.youndie.kompot.KompotAction
import io.github.youndie.kompot.UnknownAction
import io.konekt.components.konektActionWireNames
import io.konekt.kompotJson
import kotlinx.serialization.PolymorphicSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The server's half of the pair — `ClientDecodesEveryActionTest` carries the reasoning, including why
// the obvious form of this check is vacuous and why it decodes rather than asking the module what it
// has registered.
//
// The failure this side catches is the more expensive one. An action the server puts on a screen
// without having registered it is a 500 on that screen for everybody, not a button that does nothing
// for one client — which is exactly what `submit_form` did to the login screen.
class ServerEncodesEveryActionTest {
    @Test
    fun `the server's json resolves every action konekt declares`() {
        val fellBack = konektActionWireNames.filter { decodeFallsBack(it) }

        assertTrue(
            fellBack.isEmpty(),
            "the server's json decodes $fellBack as UnknownAction — add the module that registers them " +
                "in Application.kt, or drop the name from konektActionWireNames if the action is gone",
        )
    }

    @Test
    fun `the check could fail`() {
        assertTrue(konektActionWireNames.size >= 3, "konektActionWireNames has shrunk to ${konektActionWireNames.size}")
        assertEquals(
            true,
            decodeFallsBack("no_such_action_exists"),
            "an invented name did not reach the fallback, so this check proves nothing about a real one",
        )
    }

    private fun decodeFallsBack(name: String): Boolean =
        runCatching {
            kompotJson.decodeFromString(PolymorphicSerializer(KompotAction::class), """{"type":"$name"}""")
        }.getOrNull() is UnknownAction
}
