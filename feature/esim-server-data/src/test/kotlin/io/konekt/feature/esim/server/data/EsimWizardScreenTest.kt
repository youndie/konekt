package io.konekt.feature.esim.server.data

import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.decodeKompotComponent
import io.github.youndie.kompot.encodeKompotComponent
import io.github.youndie.kompot.generated.generatedKonektSerializersModule
import io.github.youndie.kompot.generated.generatedStandardSerializersModule
import io.github.youndie.kompot.kompotCoreSerializersModule
import io.github.youndie.kompot.standard.ButtonComponent
import io.github.youndie.kompot.standard.ColumnComponent
import io.github.youndie.kompot.standard.RowComponent
import io.github.youndie.kompot.standard.kompotStandardSerializersModule
import io.github.youndie.kompot.wizard.core.WizardSession
import io.github.youndie.kompot.wizard.core.WizardTransition
import io.konekt.components.BannerComponent
import io.konekt.components.EsimCardComponent
import io.konekt.components.EsimQrComponent
import io.konekt.components.EsimStatuses
import io.konekt.components.MessageTones
import io.konekt.components.StepMeterComponent
import io.konekt.feature.esim.server.domain.EsimOrderDraft
import io.konekt.feature.esim.server.domain.EsimProfile
import io.konekt.feature.esim.server.domain.EsimRefusal
import io.konekt.feature.esim.server.domain.EsimRefusals
import io.konekt.feature.esim.server.domain.EsimWizardRecord
import io.konekt.feature.esim.server.domain.EsimWizardSteps
import io.konekt.feature.esim.server.domain.EsimWizardView
import io.konekt.feature.esim.shared.api.EsimWizardStepAction
import io.konekt.feature.esim.shared.api.esimActionsSerializersModule
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

// The four frames, asserted on the tree. What this really checks is the copy and the one thing a
// screenshot could not tell you: which action each button carries.
class EsimWizardScreenTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            classDiscriminator = "type"
            serializersModule =
                kompotCoreSerializersModule +
                kompotStandardSerializersModule +
                generatedStandardSerializersModule +
                generatedKonektSerializersModule +
                esimActionsSerializersModule
        }

    private val activationCode = "LPA:1\$rsp.konekt.io\$8F214C90"

    private val profile =
        EsimProfile(
            id = "esim-1",
            subscriberId = "sub-1",
            iccid = "8944500000001234567",
            status = EsimStatuses.READY,
            activationCode = activationCode,
            createdAt = Instant.fromEpochMilliseconds(0),
        )

    private fun viewAt(
        step: String,
        refusal: EsimRefusal? = null,
        esim: EsimProfile? = null,
    ) = EsimWizardView(
        record =
            EsimWizardRecord(
                id = "wiz-1",
                subscriberId = "sub-1",
                session = WizardSession(currentStepId = step, draft = EsimOrderDraft(esim?.id)),
            ),
        refusal = refusal,
        esim = esim,
    )

    // A SCREEN THAT CANNOT SHOW THE CODE MUST NOT ASK WHETHER IT WAS SCANNED.
    //
    // This branch should now be unreachable in the product — the path that lost the profile is fixed
    // (`B-66`) — but it remains for the state it was written for: a profile issued and its row gone.
    // What was wrong is what it drew. "I have scanned it" invites the subscriber to confirm scanning
    // something that is not there, and confirming MOVES the run off the only step that would have
    // shown the code once the row came back.
    //
    // Back stays, because the copy above it tells them to go back.
    @Test
    fun `the activate step offers nothing to confirm when there is no code to scan`() {
        val screen = EsimWizardScreen.build(viewAt(EsimWizardSteps.ACTIVATE, esim = null))

        val buttons = screen.all<ButtonComponent>()
        assertTrue(
            buttons.none { it.text == "I have scanned it" },
            "the screen asked for confirmation of a scan it could not offer: ${buttons.map { it.text }}",
        )
        // Not a screen with no controls at all, which is the other way to pass the assertion above
        // and is a subscriber stuck on a step.
        assertTrue(buttons.any { it.text == "Back" }, "no way off the step: ${buttons.map { it.text }}")
    }

    // The control for the control: with a profile, the same step DOES ask. Without this the
    // assertion above passes on a wizard whose activate step lost its button entirely.
    @Test
    fun `the activate step asks about the scan when the code is on it`() {
        val screen = EsimWizardScreen.build(viewAt(EsimWizardSteps.ACTIVATE, esim = profile))

        assertTrue(
            screen.all<ButtonComponent>().any { it.text == "I have scanned it" },
            "the step that shows the code offers no way to say it was scanned",
        )
    }

    @Test
    fun `the slot limit is drawn on step one, with the meter still reading one of four`() {
        val screen =
            EsimWizardScreen.build(
                viewAt(
                    EsimWizardSteps.CHECK,
                    refusal =
                        EsimRefusal(
                            EsimRefusals.SLOT_LIMIT,
                            "This device already holds 8 eSIM profiles, which is as many as it can store. " +
                                "Remove one you no longer use, then start again.",
                        ),
                ),
            )

        val banner = assertNotNull(screen.first<BannerComponent>(), "no refusal on the screen")
        assertEquals(MessageTones.ERROR, banner.tone)
        assertTrue("Remove one" in banner.text)

        // The half a banner alone would not prove: nothing moved. A refusal drawn on step two would
        // look just as convincing in a screenshot.
        val meter = assertNotNull(screen.first<StepMeterComponent>())
        assertEquals(1, meter.current)
        assertEquals(4, meter.total)

        // And the way forward is still offered, because the subscriber can act on this one: remove a
        // profile, come back, press it again.
        assertTrue(screen.all<ButtonComponent>().any { it.text == "Continue" })
    }

    @Test
    fun `the first step offers no way back`() {
        val screen = EsimWizardScreen.build(viewAt(EsimWizardSteps.CHECK))

        // wizard-core would keep the session where it is, so a Back here does nothing visible. A
        // button that is always present and sometimes does nothing teaches people that buttons
        // sometimes do nothing.
        assertTrue(screen.all<ButtonComponent>().none { it.text == "Back" })
    }

    @Test
    fun `the qr carries the activation code and nothing else`() {
        val screen = EsimWizardScreen.build(viewAt(EsimWizardSteps.ACTIVATE, esim = profile))

        val qr = assertNotNull(screen.first<EsimQrComponent>(), "no QR on the activate step")
        assertEquals(activationCode, qr.payload)
        // The typed fallback is the matching id in fours, not the whole LPA string: what somebody is
        // asked to type is the part that names the profile.
        assertEquals("8F21-4C90", qr.manualCodeText)
    }

    @Test
    fun `the last step shows the profile and the code again`() {
        val screen = EsimWizardScreen.build(viewAt(EsimWizardSteps.DONE, esim = profile))

        val card = assertNotNull(screen.first<EsimCardComponent>())
        assertEquals(profile.iccid, card.iccid)
        assertEquals(EsimStatuses.READY, card.status)
        // Both fields, and neither derived from the other: the word is what the client branches on,
        // the sentence is what the subscriber reads.
        assertTrue(card.statusText.isNotBlank())

        // Kept rather than cleared, for the subscriber whose camera would not read it.
        assertEquals(activationCode, assertNotNull(screen.first<EsimQrComponent>()).payload)
    }

    @Test
    fun `the last step finishes the run instead of stepping past its end`() {
        val screen = EsimWizardScreen.build(viewAt(EsimWizardSteps.DONE, esim = profile))

        val done = assertNotNull(screen.all<ButtonComponent>().firstOrNull { it.text == "Done" })
        // Next on the last step stays put — the resolver answers null — so a Done wired to Next would
        // be a button that visibly does nothing.
        assertEquals(EsimWizardStepAction("wiz-1", WizardTransition.Finish), done.action)
    }

    @Test
    fun `every button carries this run's id`() {
        EsimWizardSteps.order.forEach { step ->
            val screen = EsimWizardScreen.build(viewAt(step, esim = profile))
            val actions = screen.all<ButtonComponent>().map { it.action }

            assertTrue(actions.isNotEmpty(), "$step has no way out")
            actions.forEach { action ->
                assertEquals("wiz-1", assertNotNull(action as? EsimWizardStepAction, "$step: $action").wizardId)
            }
        }
    }

    @Test
    fun `every step's screen survives the wire`() {
        // The tree is worth nothing if it does not reach a client, and the action inside a button is
        // the part nothing else here would catch: it is registered by hand rather than generated.
        EsimWizardSteps.order.forEach { step ->
            val screen = EsimWizardScreen.build(viewAt(step, esim = profile))
            assertEquals(screen, json.decodeKompotComponent(json.encodeKompotComponent(screen)), "$step")
        }
    }

    private inline fun <reified T : KompotComponent> KompotComponent.first(): T? = all<T>().firstOrNull()

    private inline fun <reified T : KompotComponent> KompotComponent.all(): List<T> = walk().filterIsInstance<T>()

    private fun KompotComponent.walk(): List<KompotComponent> =
        listOf(this) +
            when (this) {
                is ColumnComponent -> children.flatMap { it.walk() }
                is RowComponent -> children.flatMap { it.walk() }
                else -> emptyList()
            }
}
