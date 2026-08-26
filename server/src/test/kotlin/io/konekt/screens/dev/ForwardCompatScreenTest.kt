package io.konekt.screens.dev

import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.UnknownComponent
import io.github.youndie.kompot.decodeKompotComponent
import io.github.youndie.kompot.encodeKompotComponent
import io.github.youndie.kompot.generated.generatedKonektSerializersModule
import io.github.youndie.kompot.generated.generatedStandardSerializersModule
import io.github.youndie.kompot.kompotCoreSerializersModule
import io.github.youndie.kompot.standard.ColumnComponent
import io.github.youndie.kompot.standard.kompotStandardSerializersModule
import io.konekt.components.UsageCounterCardComponent
import io.konekt.devScreensRouteGroup
import io.konekt.konektRoutes
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.plus
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// THE SCREEN THAT PROVES A CLAIM NOTHING ELSE CAN.
//
// The canvas has drawn the unknown-component block since before there was code, labelled with this
// exact wire name. Until this screen existed the frame was a picture of a state the product could not
// enter: the client registers every type the server sends, so the replacement renderer was exercised
// only by fixtures constructing an `UnknownComponent` directly — which tests the renderer and says
// nothing about whether an unknown component can ever ARRIVE.
class ForwardCompatScreenTest {
    // The SERVER's Json, which knows the dev type because the server has to put it on the wire.
    private val serverJson =
        Json {
            classDiscriminator = "type"
            serializersModule =
                kompotCoreSerializersModule + kompotStandardSerializersModule +
                generatedStandardSerializersModule + generatedKonektSerializersModule +
                SerializersModule {
                    polymorphic(KompotComponent::class) { subclass(EsimTransferWidgetComponent::class) }
                }
        }

    // THE CLIENT'S Json, assembled from exactly what a client has: the toolkit's modules and the
    // generated dictionary, and nothing of the server's. This is the whole fixture — if this Json
    // could decode the type, the screen would be demonstrating nothing.
    private val clientJson =
        Json {
            ignoreUnknownKeys = true
            classDiscriminator = "type"
            serializersModule =
                kompotCoreSerializersModule + kompotStandardSerializersModule +
                generatedStandardSerializersModule + generatedKonektSerializersModule
        }

    private fun asClientSees(): ColumnComponent =
        clientJson.decodeKompotComponent(
            serverJson.encodeKompotComponent(ForwardCompatScreen.build()),
        ) as ColumnComponent

    @Test
    fun `the client cannot decode the dev type, and gets the block instead`() {
        val unknown = asClientSees().children.filterIsInstance<UnknownComponent>()

        assertEquals(2, unknown.size, "expected both dev components to arrive unknown")
        // The type name survives, which is what lets the block say what it could not draw and what
        // lets a degradation record name it.
        assertTrue(
            unknown.all { it.originalType == "esim_transfer_widget" },
            "the unknown components lost their type: ${unknown.map { it.originalType }}",
        )
    }

    @Test
    fun `everything around the unknown components still arrives`() {
        // The claim the screen exists for, and the reason it has known neighbours above and below. A
        // screen containing only the unknown component would look identical whether the rest of the
        // tree survived or not.
        val counters = asClientSees().children.filterIsInstance<UsageCounterCardComponent>()

        assertEquals(2, counters.size, "the known neighbours did not survive")
        assertEquals("9.7 GB left", counters.first().valueText)
    }

    @Test
    fun `the development screen is not in the production route table`() {
        // The second acceptance criterion, and it is about the TABLE rather than about a flag. A
        // development route reaches a deployment by being in the list every deployment mounts, so
        // that is what is asserted: `konektRoutes` is what `module` mounts unconditionally, and the
        // dev group is added beside it only under `DEV_SCREENS`.
        assertTrue(
            devScreensRouteGroup !in konektRoutes,
            "the development screen group is in the production route table",
        )
        assertEquals(2, konektRoutes.size, "the production table changed size — is a development group in it?")
    }
}
