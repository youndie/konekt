package io.konekt.components

import io.github.youndie.kompot.KompotAction
import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.KompotModifierNode
import io.github.youndie.kompot.registry.KompotComponentMarker
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// A quota and what is left of it: the control the subscriber opens the application to look at, and
// the reason `usage_counter_card` is the first type in this dictionary.
//
// WHY THE TEXT IS PRE-FORMATTED. `valueText` is "15,8 GB left" and not a number with a unit, and
// `captionText` is "Minutes run out in about two days at your current pace. A 100-minute add-on
// costs 149 ₽." That is the backend-driven bargain taken deliberately: the server builds the screen,
// so the server formats, and a client that cannot format cannot format inconsistently. It also
// removes the second copy of every formatter — the one that historically appears in a view model and
// divides by a hard-coded hundred.
//
// `progress` is the exception and has to be, because it is geometry rather than language: a bar
// needs a fraction. It is 0..1 and nullable, since a counter with no ceiling (an unlimited plan) has
// a value to show and no bar to draw.
@Serializable
@SerialName("usage_counter_card")
@KompotComponentMarker
data class UsageCounterCardComponent(
    override val id: String,
    override val modifiers: List<KompotModifierNode> = emptyList(),
    // "Data", "Minutes", "Messages" — the quota's name, already in the subscriber's language.
    val title: String,
    val valueText: String,
    // The projection, the warning, or the offer. Absent in the ordinary state; the canvas fills it in
    // the low one, and the copy there is what makes `state` worth carrying at all.
    val captionText: String? = null,
    val progress: Float? = null,
    // One of CounterStates. Open string: an unknown word draws the ordinary card rather than nothing.
    val state: String = CounterStates.NORMAL,
    val action: KompotAction? = null,
    // DRAWN AS A ROW INSIDE SOMETHING ELSE, rather than as a card with its own ground.
    //
    // The canvas groups the three allowances under one head — `B-60` settled that the container for
    // it exists and that what blocked it was the domain, not the wire — so the home screen puts them
    // in a `surface` and each counter becomes a line in it: the label and the value on one baseline,
    // a full-width bar under them, the caption below that. A card inside a card is two grounds and a
    // double padding, which is what this flag is for and why it is not a second component: the
    // content is identical, only the chrome differs.
    //
    // False by default, because the travel screen still draws each package as its own card and a
    // default that changed under it would be this change reaching a screen that did not ask.
    val inline: Boolean = false,
) : KompotComponent
