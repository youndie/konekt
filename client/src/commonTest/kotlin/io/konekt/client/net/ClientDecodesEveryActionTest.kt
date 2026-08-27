package io.konekt.client.net

import io.github.youndie.kompot.KompotAction
import io.github.youndie.kompot.UnknownAction
import io.konekt.components.konektActionWireNames
import kotlinx.serialization.PolymorphicSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// EVERY ACTION THIS BUILD PUTS ON THE WIRE MUST DECODE HERE, and nothing about the build says so.
//
// Components are generated: a `@KompotComponentMarker` type reaches the registry through KSP, and
// forgetting one breaks the build. Actions are registered BY HAND, on each side, and leaving one out
// compiles perfectly. It has cost this repository three incidents; the last was `submit_form`,
// registered on neither side, which answered 500 on the login screen. The comment above
// `konektClientJson` claimed a test was watching this seam. There was no such test.
//
// THE OBVIOUS VERSION OF THIS CHECK IS VACUOUS, and it was written first: asking the module whether
// it has a registration for a name — `getPolymorphic(KompotAction::class, name)` — answers something
// for EVERY string, because kompot installs a polymorphic default so that an unknown action degrades
// instead of throwing. That check passed with the registration deleted. It is also why the third
// incident was silent on this side: an unregistered action does not fail here, it quietly becomes
// `UnknownAction`, and a button then does nothing for a reason nothing reports.
//
// So the check DECODES. A name that reached a real serializer either produces its action or is
// refused for a missing field — both prove the serializer was chosen. `UnknownAction` is the one
// answer that means nobody registered it.
class ClientDecodesEveryActionTest {
    @Test
    fun `the client's json resolves every action konekt declares`() {
        val fellBack = konektActionWireNames.filter { decodeFallsBack(it) }

        assertTrue(
            fellBack.isEmpty(),
            "konektClientJson decodes $fellBack as UnknownAction — add the module that registers them " +
                "to konektClientJson, or drop the name from konektActionWireNames if the action is gone",
        )
    }

    // The control, and it is the half that makes the assertion above mean anything. If an invented
    // name did NOT fall back, the module would be answering everything and the check would be green
    // on a build with no registrations at all.
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
            konektClientJson.decodeFromString(PolymorphicSerializer(KompotAction::class), """{"type":"$name"}""")
        }.getOrNull() is UnknownAction
}
