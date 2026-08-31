package io.konekt.components

import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.KompotModifierNode
import io.github.youndie.kompot.registry.KompotComponentMarker
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// A QUANTITY CHOSEN ALONG A RANGE, and the twelfth name konekt adds to the wire.
//
// `B-87` closed this question the other way and was right at the time: kompot's field set is text,
// amount, checkbox, autocomplete and selection, there is no slider, and three `select_input`s is what
// the toolkit has. What changed is not the toolkit — it is that this dictionary already carries
// eleven types of konekt's own, each added because kompot's vocabulary did not carry the product's
// meaning. A quantity picked from an ordered range, with a price that moves as it moves, is that case
// exactly, and a dropdown is the workaround (`B-104`).
//
// THE STEPS TRAVEL, NOT A MIN AND A MAX. The server prices from a fixed list per quantity —
// `CustomPackageTariff.DATA_GB_STEPS` and its two siblings — and refuses anything off it. A component
// carrying a continuous range would let a client propose a size the server will not sell, which is
// the same list-on-both-sides rule the selects already followed and the reason this is not a range
// input with an increment.
//
// WHAT IT COSTS, said here because the dictionary is the API: a client that has not learned this name
// draws konekt's unknown block and reports the type to the degradation sink. That is a labelled hole
// rather than a blank screen, and it is what makes the word shippable ahead of every client — the
// server can go on sending `select_input` to anything older, because the form's schema is served per
// request rather than compiled into the client.
@Serializable
@SerialName("slider_input")
@KompotComponentMarker
data class SliderInputComponent(
    override val id: String,
    override val modifiers: List<KompotModifierNode> = emptyList(),
    // WHICH FIELD OF THE FORM this moves. The same binding `select_input` has: the renderer reads the
    // value out of the `FormController` it is handed and writes back through it, so the patch, the
    // validation and the submit all go on working without knowing a slider exists.
    val fieldId: String,
    val label: String,
    // THE ALLOWED VALUES, IN ORDER, as text. Text rather than numbers because the wire already
    // renders every quantity as a string and the client draws what it is given — and because a step
    // list that is not a number today (a tier, a size name) needs no second component.
    //
    // A slider with fewer than two steps is a control that cannot move; the renderer draws the label
    // and the value and no track, rather than a slider that looks broken.
    val steps: List<String>,
    // What follows the number when it is drawn — "GB" and the like. Absent for a bare count.
    val unit: String? = null,
) : KompotComponent
