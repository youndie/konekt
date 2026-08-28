package io.konekt.feature.esim.server.data

import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.standard.ButtonComponent
import io.github.youndie.kompot.standard.ColumnComponent
import io.github.youndie.kompot.standard.RowComponent
import io.github.youndie.kompot.standard.TextComponent
import io.github.youndie.kompot.wizard.core.WizardTransition
import io.konekt.components.BannerComponent
import io.konekt.components.ButtonEmphasis
import io.konekt.components.EsimCardComponent
import io.konekt.components.EsimQrComponent
import io.konekt.components.EsimStatuses
import io.konekt.components.MessageTones
import io.konekt.components.StepMeterComponent
import io.konekt.feature.esim.server.domain.EsimProfile
import io.konekt.feature.esim.server.domain.EsimWizardSteps
import io.konekt.feature.esim.server.domain.EsimWizardView
import io.konekt.feature.esim.shared.api.EsimWizardStepAction

// The install flow, drawn on the server, one screen per step.
//
// EVERY BUTTON CARRIES ITS OWN ACTION, and the action is what the client posts back unchanged. That
// is what keeps the flow's shape here: the client never decides which transition a button makes, so
// there is no second copy of the graph to drift from this one.
object EsimWizardScreen {
    fun build(view: EsimWizardView): KompotComponent {
        val step = view.record.session.currentStepId
        val wizardId = view.record.id

        return ColumnComponent(
            id = "esim-wizard",
            spacing = 16,
            children =
                buildList {
                    add(
                        StepMeterComponent(
                            // Derived from the subject rather than generated, so a live update can
                            // name the node it replaces.
                            id = "esim-wizard-progress",
                            current = EsimWizardSteps.indexOf(step),
                            total = EsimWizardSteps.total,
                            label = "Add an eSIM",
                        ),
                    )

                    // THE REFUSAL SITS ON THE STEP THAT REFUSED, above its content and below the
                    // meter — which still reads "1 of 4", because nothing moved. That is the frame
                    // the canvas draws, and it is only reachable because the refusal travels in the
                    // view instead of as a status code.
                    view.refusal?.let { refusal ->
                        add(
                            BannerComponent(
                                id = "esim-wizard-refusal",
                                text = refusal.text,
                                tone = MessageTones.ERROR,
                            ),
                        )
                    }

                    addAll(contentOf(step, view.esim))
                    add(controlsOf(step, wizardId, view.esim))
                },
        )
    }

    private fun contentOf(
        step: String,
        esim: EsimProfile?,
    ): List<KompotComponent> =
        when (step) {
            EsimWizardSteps.CHECK -> {
                listOf(
                    TextComponent(
                        id = "esim-wizard-check",
                        text =
                            "An eSIM is a profile your phone downloads — there is nothing to post and nothing " +
                                "to swap. It takes about a minute, and you can keep using this line while it " +
                                "installs.",
                    ),
                )
            }

            EsimWizardSteps.CONFIRM -> {
                listOf(
                    TextComponent(
                        id = "esim-wizard-confirm",
                        text =
                            "We will ask for a new profile for this number. You can install it straight away, " +
                                "or keep the code and install it later — it does not expire.",
                    ),
                )
            }

            EsimWizardSteps.ACTIVATE -> {
                activateContent(esim)
            }

            EsimWizardSteps.DONE -> {
                doneContent(esim)
            }

            // An unrecognised step id can only mean a row written by a build that knew more than this
            // one. Drawing nothing would be a blank screen with a Back button; saying so leaves the
            // subscriber somewhere they can act.
            else -> {
                listOf(
                    TextComponent(
                        id = "esim-wizard-unknown-step",
                        text = "This step is not available in this version of the app. Update to continue.",
                    ),
                )
            }
        }

    private fun activateContent(esim: EsimProfile?): List<KompotComponent> {
        val code =
            esim?.activationCode
                // Only reachable if the profile was issued and the row then vanished, which is a
                // broken state rather than a slow one. Saying so beats a QR frame with nothing in it.
                ?: return listOf(
                    TextComponent(
                        id = "esim-wizard-activate-missing",
                        text = "We could not read your activation code. Go back and try again.",
                    ),
                )

        return listOf(
            qrOf(code, id = "esim-wizard-qr"),
            TextComponent(
                id = "esim-wizard-activate",
                text =
                    "Open Settings, add an eSIM, and point the camera at this code. If the camera will not " +
                        "read it, type the code underneath instead.",
            ),
        )
    }

    private fun doneContent(esim: EsimProfile?): List<KompotComponent> =
        buildList {
            add(
                BannerComponent(
                    id = "esim-wizard-done",
                    text = "Your eSIM is ready.",
                    tone = MessageTones.INFO,
                ),
            )

            esim?.let { profile ->
                add(
                    EsimCardComponent(
                        id = "esim-${profile.id}",
                        label = "New line",
                        iccid = profile.iccid,
                        status = profile.status,
                        statusText = statusTextFor(profile.status),
                    ),
                )

                // THE CODE IS SHOWN AGAIN, and this is the point of the last step rather than a
                // duplicate of the one before it. Somebody arrives here having failed to scan — the
                // camera would not focus, the sheet was dismissed — and a flow that takes the code
                // away at the end is a flow that hides the one thing still worth having.
                profile.activationCode?.let { add(qrOf(it, id = "esim-wizard-qr-again")) }
            }
        }

    private fun qrOf(
        activationCode: String,
        id: String,
    ): EsimQrComponent =
        EsimQrComponent(
            id = id,
            // The code itself, never an image. An image needs a URL, a URL is fetched, and a fetched
            // URL puts a credential into a query string and into somebody's access log.
            payload = activationCode,
            captionText = "Stay on Wi-Fi. This takes up to a minute and finishes on its own.",
            manualCodeText = manualCodeOf(activationCode),
        )

    // The matching id, in fours, for somebody typing it by hand off their own screen.
    //
    // Not the whole `LPA:1$…$…` string: what a person is asked to type is the part that identifies
    // the profile, and the rest is scheme and hostname they would only get wrong.
    internal fun manualCodeOf(activationCode: String): String =
        activationCode
            .substringAfterLast('$')
            .chunked(4)
            .joinToString("-")

    private fun statusTextFor(status: String): String =
        when (status) {
            EsimStatuses.READY -> "Installs as an eSIM by QR code. Your device supports it."

            EsimStatuses.INSTALLED -> "Installed on this device."

            EsimStatuses.ACTIVE -> "In use."

            EsimStatuses.SUSPENDED -> "Paused. Contact support to resume it."

            EsimStatuses.TERMINATED -> "No longer usable."

            EsimStatuses.ORDERED -> "Being prepared. This usually takes under a minute."

            // The sentence is the subscriber's and the word above is the client's, so an unfamiliar
            // word still gets a sentence. See EsimCardComponent for why both exist.
            else -> "This profile is in a state this version of the app does not describe."
        }

    private fun controlsOf(
        step: String,
        wizardId: String,
        esim: EsimProfile?,
    ): KompotComponent {
        val back =
            ButtonComponent(
                id = "esim-wizard-back",
                text = "Back",
                action = EsimWizardStepAction(wizardId, WizardTransition.Back),
                variant = ButtonEmphasis.QUIET,
            )

        val forward =
            when (step) {
                EsimWizardSteps.CHECK -> {
                    forwardButton(wizardId, "Continue")
                }

                EsimWizardSteps.CONFIRM -> {
                    forwardButton(wizardId, "Get my eSIM")
                }

                EsimWizardSteps.ACTIVATE -> {
                    // NOTHING TO HAVE SCANNED. When the code could not be read, the content above is
                    // an apology and this button asks the subscriber to confirm scanning something
                    // that is not on the screen — and confirming MOVES the run, so the one state that
                    // still had the code behind it is left behind.
                    //
                    // Only Back, which is what the copy above already tells them to press. This is
                    // the second half of `B-66`: the first half is that the branch should be
                    // unreachable, and a control that contradicts its own screen is worth removing
                    // whatever made the screen say it.
                    esim?.activationCode?.let { forwardButton(wizardId, "I have scanned it") }
                }

                EsimWizardSteps.DONE -> {
                    ButtonComponent(
                        id = "esim-wizard-finish",
                        text = "Done",
                        // Finish and not Next. On the last step wizard-core's resolver answers null,
                        // so a Next would stay put and the button would do nothing visible — which is
                        // exactly the bug this distinction exists to prevent.
                        action = EsimWizardStepAction(wizardId, WizardTransition.Finish),
                        variant = ButtonEmphasis.PRIMARY,
                    )
                }

                else -> {
                    forwardButton(wizardId, "Continue")
                }
            }

        // No Back on the first step: there is nowhere to go, and wizard-core would keep the session
        // where it is. A button that is always there and sometimes does nothing teaches people that
        // buttons sometimes do nothing.
        // `forward` is nullable now, and only for the step above. `listOfNotNull` rather than a
        // branch on the step, so a step that later has nothing to go forward to does the right thing
        // without this line being edited again.
        val buttons =
            if (step == EsimWizardSteps.CHECK) listOfNotNull(forward) else listOfNotNull(back, forward)

        return RowComponent(
            id = "esim-wizard-controls",
            spacing = 12,
            children = buttons,
        )
    }

    private fun forwardButton(
        wizardId: String,
        text: String,
    ): ButtonComponent =
        ButtonComponent(
            id = "esim-wizard-next",
            text = text,
            action = EsimWizardStepAction(wizardId, WizardTransition.Next),
            variant = ButtonEmphasis.PRIMARY,
        )
}
