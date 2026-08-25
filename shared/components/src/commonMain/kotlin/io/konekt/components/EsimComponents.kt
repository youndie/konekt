package io.konekt.components

import io.github.youndie.kompot.KompotAction
import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.KompotModifierNode
import io.github.youndie.kompot.registry.KompotComponentMarker
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// One eSIM the subscriber holds: what it is, what state it is in, and what may be done to it now.
//
// `status` and `statusText` are both here and neither is redundant. The word is for the client's
// behaviour — which affordance to draw, which action to enable — and the sentence is for the
// subscriber, who is told "Installs as an eSIM by QR code. Your device supports it" rather than
// "READY". A client deriving the sentence from the word would be writing product copy, which is the
// thing this architecture keeps on the server.
@Serializable
@SerialName("esim_card")
@KompotComponentMarker
data class EsimCardComponent(
    override val id: String,
    override val modifiers: List<KompotModifierNode> = emptyList(),
    val label: String,
    // Nineteen or twenty digits. Drawn in the monospaced face, because it is read character by
    // character when somebody reads it aloud to support.
    val iccid: String,
    // One of EsimStatuses. Open string: an unfamiliar lifecycle state draws the neutral card and
    // keeps the rest of the screen, which is the whole point of degradation.
    val status: String,
    val statusText: String,
    val action: KompotAction? = null,
) : KompotComponent

// An activation code, rendered as a QR by the CLIENT.
//
// The component carries the `LPA:1$...$...` string and nothing else, and that is the decision: the
// server never turns an activation code into an image. An image needs a URL, a URL is fetched, and a
// fetched URL puts a credential — which is what an activation code is — into a query string and into
// somebody's access log. Drawing it locally keeps it inside the process that is allowed to see it.
//
// It is also why this is a component of ours rather than a `kompot-images` URL.
@Serializable
@SerialName("esim_qr")
@KompotComponentMarker
data class EsimQrComponent(
    override val id: String,
    override val modifiers: List<KompotModifierNode> = emptyList(),
    val payload: String,
    // "Stay on Wi-Fi. This takes up to a minute and finishes on its own." — what to do while looking
    // at it.
    val captionText: String? = null,
    // The same code as text, for the subscriber who cannot scan their own screen. Absent when the
    // flow hands them a system sheet instead.
    val manualCodeText: String? = null,
) : KompotComponent
