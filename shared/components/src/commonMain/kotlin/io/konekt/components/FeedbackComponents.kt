package io.konekt.components

import io.github.youndie.kompot.KompotAction
import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.KompotModifierNode
import io.github.youndie.kompot.registry.KompotComponentMarker
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Something the screen wants to say, in the flow of the screen.
@Serializable
@SerialName("banner")
@KompotComponentMarker
data class BannerComponent(
    override val id: String,
    override val modifiers: List<KompotModifierNode> = emptyList(),
    val text: String,
    // One of MessageTones. An unknown tone draws the neutral banner: the message still reaches the
    // subscriber, which matters more than its colour.
    val tone: String = MessageTones.INFO,
    val actionText: String? = null,
    val action: KompotAction? = null,
) : KompotComponent

// The same message, transient and outside the tree.
//
// A separate type from `banner` rather than a tone of it, because the difference is not appearance
// but lifetime: a banner is part of the layout and a snackbar is not, and a client that received one
// where it expected the other would put a disappearing element in the middle of a column.
@Serializable
@SerialName("snackbar")
@KompotComponentMarker
data class SnackbarComponent(
    override val id: String,
    override val modifiers: List<KompotModifierNode> = emptyList(),
    val text: String,
    val tone: String = MessageTones.INFO,
    val actionText: String? = null,
    val action: KompotAction? = null,
) : KompotComponent

// "Step 3 of 4" — a wizard's own progress.
//
// Not a generic progress bar, and the two numbers rather than a fraction are the reason: a subscriber
// four steps into an install wants to know how many are left, and 0.75 does not answer that. The
// label is optional because the numbers alone are already the answer on a narrow screen.
@Serializable
@SerialName("step_meter")
@KompotComponentMarker
data class StepMeterComponent(
    override val id: String,
    override val modifiers: List<KompotModifierNode> = emptyList(),
    // One-based, the way it is spoken.
    val current: Int,
    val total: Int,
    val label: String? = null,
) : KompotComponent

// What stands where a real one is still loading.
//
// It is on the wire rather than being a client-side flag because only the server knows a list is
// being fetched rather than genuinely empty, and those two look identical drawn as nothing. The
// canvas puts a loading row in the same frame as the available and sold-out ones for that reason.
@Serializable
@SerialName("skeleton")
@KompotComponentMarker
data class SkeletonComponent(
    override val id: String,
    override val modifiers: List<KompotModifierNode> = emptyList(),
    // One of SkeletonShapes.
    val shape: String = SkeletonShapes.ROW,
    // How many to draw. A list waiting for three rows should shimmer three times, or the wait itself
    // changes the layout when the answer arrives.
    val count: Int = 1,
) : KompotComponent
