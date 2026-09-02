package io.konekt.feature.esim.server.data

import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.KompotModifierNode
import io.github.youndie.kompot.SizeType
import io.github.youndie.kompot.material3.M3Colors
import io.github.youndie.kompot.material3.M3Typography
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
import io.konekt.components.IconComponent
import io.konekt.components.MessageTones
import io.konekt.components.ScreenHeaderComponent
import io.konekt.components.StepMeterComponent
import io.konekt.components.SurfaceComponent
import io.konekt.components.VectorIcon
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
                    add(headerOf(step, wizardId))
                    add(
                        StepMeterComponent(
                            // Derived from the subject rather than generated, so a live update can
                            // name the node it replaces.
                            id = "esim-wizard-progress",
                            current = EsimWizardSteps.indexOf(step),
                            total = EsimWizardSteps.total,
                        ),
                    )

                    // THE REFUSAL SITS ON THE STEP THAT REFUSED, above its content and below the
                    // meter — which still reads "1 of 4", because nothing moved. That is the frame
                    // the canvas draws, and it is only reachable because the refusal travels in the
                    // view instead of as a status code.
                    // THE REFUSAL AS THE CANVAS DRAWS A FAILED CHECK (`B-115`): an amber disc with
                    // the mark beside the sentence, in a card — the row the checklist would have
                    // had, without the checklist this build cannot honestly draw. It was a bordered
                    // error banner over the content, which read as the application failing rather
                    // than as one fact about the device.
                    view.refusal?.let { refusal ->
                        add(
                            SurfaceComponent(
                                id = "esim-wizard-refusal",
                                children =
                                    listOf(
                                        RowComponent(
                                            id = "esim-wizard-refusal-row",
                                            spacing = 12,
                                            children =
                                                listOf(
                                                    IconComponent(
                                                        id = "esim-wizard-refusal-mark",
                                                        icon = EXCLAMATION,
                                                        tone = MessageTones.LOW,
                                                        size = 40,
                                                    ),
                                                    TextComponent(
                                                        id = "esim-wizard-refusal-text",
                                                        text = refusal.text,
                                                        style = M3Typography.BodyMedium,
                                                        color = M3Colors.OnSurface,
                                                        modifiers = listOf(KompotModifierNode.Weight(1f)),
                                                    ),
                                                ),
                                        ),
                                    ),
                            ),
                        )
                    }
                    addAll(contentOf(step, view.esim))
                    controlsOf(step, wizardId, view.esim)?.let(::add)
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
                    heading("Before you start"),
                    TextComponent(
                        id = "esim-wizard-check",
                        style = M3Typography.BodyLarge,
                        color = M3Colors.OnSurfaceVariant,
                        text =
                            "An eSIM is a profile your phone downloads — there is nothing to post and nothing " +
                                "to swap. It takes about a minute, and you can keep using this line while it " +
                                "installs.",
                    ),
                )
            }

            EsimWizardSteps.CONFIRM -> {
                listOf(
                    heading("Get your eSIM"),
                    TextComponent(
                        id = "esim-wizard-confirm",
                        style = M3Typography.BodyLarge,
                        color = M3Colors.OnSurfaceVariant,
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
            // The code on its tile, in a card (`B-115` W6): the canvas's QR block is a white card
            // with the tile centred in it, and the paragraph is the body under it.
            SurfaceComponent(id = "esim-wizard-qr-card", children = listOf(qrOf(code, id = "esim-wizard-qr"))),
            TextComponent(
                id = "esim-wizard-activate",
                style = M3Typography.BodyLarge,
                color = M3Colors.OnSurfaceVariant,
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

    // ONE HEADING PER STEP (`B-115` W4), in the canvas's `headline_medium`, over a body in
    // `body_large` — the screen used to open on prose. `activate` has none: its header already
    // says *Scan or install*, and the card comes next.
    private fun heading(text: String): TextComponent =
        TextComponent(
            id = "esim-wizard-title",
            text = text,
            style = M3Typography.HeadlineMedium,
            color = M3Colors.OnSurface,
        )

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

    // ONE BACK, AND IT IS THE HEADER'S (`B-115`). The circle on the first step is a cross that leaves
    // — the shell's business, so no action travels; on every other step it is the chevron that goes
    // a step back, which is the wizard's business and travels as its own transition. The `Back` pill
    // that used to sit under the shell's chevron is gone: two controls that went different ways
    // with nothing on screen to tell them apart.
    private fun headerOf(
        step: String,
        wizardId: String,
    ): ScreenHeaderComponent =
        when (step) {
            EsimWizardSteps.CHECK -> {
                ScreenHeaderComponent(id = "esim-wizard-header", title = "Install eSIM", closes = true)
            }

            EsimWizardSteps.ACTIVATE -> {
                ScreenHeaderComponent(
                    id = "esim-wizard-header",
                    title = "Scan or install",
                    action = EsimWizardStepAction(wizardId, WizardTransition.Back),
                )
            }

            // A FINISHED FLOW HAS NO BACK: going back from `done` re-issues nothing and confuses
            // everything, so the cross finishes — the same transition `Done` sends.
            EsimWizardSteps.DONE -> {
                ScreenHeaderComponent(
                    id = "esim-wizard-header",
                    title = "Install eSIM",
                    action = EsimWizardStepAction(wizardId, WizardTransition.Finish),
                    closes = true,
                )
            }

            else -> {
                ScreenHeaderComponent(
                    id = "esim-wizard-header",
                    title = "Install eSIM",
                    action = EsimWizardStepAction(wizardId, WizardTransition.Back),
                )
            }
        }

    // THE WAY FORWARD, PINNED above the bottom edge and full width, the way the canvas draws it and
    // the way the plan page's buy button is drawn since `B-114`. The step that cannot go forward —
    // `activate` without a code — pins nothing, and the header is still the way back.
    private fun controlsOf(
        step: String,
        wizardId: String,
        esim: EsimProfile?,
    ): KompotComponent? {
        val forward =
            when (step) {
                EsimWizardSteps.CHECK -> {
                    forwardButton(wizardId, "Continue")
                }

                EsimWizardSteps.CONFIRM -> {
                    forwardButton(wizardId, "Get my eSIM")
                }

                EsimWizardSteps.ACTIVATE -> {
                    esim?.activationCode?.let { forwardButton(wizardId, "I have scanned it") }
                }

                EsimWizardSteps.DONE -> {
                    ButtonComponent(
                        id = "esim-wizard-finish",
                        text = "Done",
                        action = EsimWizardStepAction(wizardId, WizardTransition.Finish),
                        variant = ButtonEmphasis.PRIMARY,
                        modifiers = listOf(KompotModifierNode.Size(width = SizeType.Fill)),
                    )
                }

                else -> {
                    forwardButton(wizardId, "Continue")
                }
            } ?: return null
        return SurfaceComponent(id = "esim-wizard-controls", pinned = true, children = listOf(forward))
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
            modifiers = listOf(KompotModifierNode.Size(width = SizeType.Fill)),
        )
}

// The mark on a failed check, on the 24-grid every icon in this build is drawn on: a bar and a dot.
private val EXCLAMATION = VectorIcon(paths = listOf("M12 7v6", "M12 16.5v.5"))
