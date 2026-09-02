package io.konekt.components

import io.github.youndie.kompot.KompotAction
import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.KompotModifierNode
import io.github.youndie.kompot.registry.KompotComponentMarker
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// A SCREEN'S OWN HEADER: the circle on the left and the title beside it, the way the canvas opens
// every step of the install flow (`B-115`). The circle presses `action` — a wizard's own step back —
// or, with no action, leaves the screen the way the shell's chevron does. `closes` picks the glyph:
// a cross for a step that leaves, a chevron for one that goes back.
//
// It exists because the shell was drawing its chevron over a wizard that also drew a `Back` pill,
// and the two did different things with nothing on screen to say which. A screen that carries this
// owns its back control: the shell pulls it out of the tree the way it pulls the bar and the pinned
// footer, and draws it in the chevron's place. A client that predates it draws it in place through
// the registry, which is a header at the top of the content rather than above it — wrong by a few
// points, not by a control.
@Serializable
@SerialName("screen_header")
@KompotComponentMarker
data class ScreenHeaderComponent(
    override val id: String,
    override val modifiers: List<KompotModifierNode> = emptyList(),
    val title: String,
    val action: KompotAction? = null,
    val closes: Boolean = false,
) : KompotComponent
