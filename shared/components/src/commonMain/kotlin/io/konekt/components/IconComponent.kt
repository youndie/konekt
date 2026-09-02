package io.konekt.components

import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.KompotModifierNode
import io.github.youndie.kompot.registry.KompotComponentMarker
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// A SHAPE IN A CIRCLE, and the second place a `VectorIcon` is drawn — which is what `B-110` said
// would decide whether a shape deserved a component of its own rather than a field on the bar.
//
// The canvas opens every purchase outcome with one: a check in a filled primary circle over `Paid.`,
// a cross in the error container over `Payment failed.` The tone chooses the pair of colours and the
// client resolves them from the brand kit, exactly as a banner does; the shape travels as path data
// for the reasons `VectorIcon` gives, and its colour deliberately does not.
@Serializable
@SerialName("icon")
@KompotComponentMarker
data class IconComponent(
    override val id: String,
    override val modifiers: List<KompotModifierNode> = emptyList(),
    val icon: VectorIcon,
    // The same open vocabulary the banner reads — `info`, `low`, `error` — and the same bargain: a
    // word this build does not know draws the neutral form.
    val tone: String = MessageTones.INFO,
    // The circle, in points. 88 is the canvas's outcome mark; a row-sized one is 40.
    val size: Int = 88,
) : KompotComponent
