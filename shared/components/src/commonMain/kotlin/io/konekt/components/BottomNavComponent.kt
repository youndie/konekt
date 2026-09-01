package io.konekt.components

import io.github.youndie.kompot.KompotAction
import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.KompotModifierNode
import io.github.youndie.kompot.registry.KompotComponentMarker
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// The application's shell, and it is on the wire rather than in the client on purpose.
//
// A bar compiled into the client would be the one part of this product the server cannot change,
// which for a backend-driven product is a strange thing to be proud of — and it would contradict the
// claim `B-22` makes about colour, that a brand is a redeploy rather than a rebuild. The tabs, their
// labels and their order are a product decision, so they travel like every other product decision.
//
// IT IS NOT A CONTAINER. The bar carries destinations and nothing renders inside it, so a screen is
// still one tree: the shell HOISTS this component out of the tree it was sent in and draws it in the
// scaffold, which is why it may appear at most once and why its position among its siblings does not
// matter.
@Serializable
@SerialName("bottom_nav")
@KompotComponentMarker
data class BottomNavComponent(
    override val id: String,
    override val modifiers: List<KompotModifierNode> = emptyList(),
    val items: List<BottomNavItem>,
) : KompotComponent

// One destination.
//
// `action` rather than a bare deeplink, so a tab travels the same road as every other transition in
// this build — the banner's "See plans" is a `NavigateAction` too, and a second mechanism for the
// same thing is a second place for it to break. What that buys concretely: the client's action
// handling is unchanged, and a tab that one day needs to do something other than navigate can.
//
// THE ICON IS DATA, not a name (`B-110`). It used to be nothing at all, and the reasoning for that
// still stands against the version it was written about: a name here plus a table in the client is a
// second dictionary kept in step by hand, failing silently when they drift. `VectorIcon` says why the
// shape travels instead, and why its colour does not.
@Serializable
data class BottomNavItem(
    val label: String,
    val action: KompotAction,
    // NULLABLE, and it stays nullable. A bar is legible without icons — that is what this build
    // shipped for two seasons — so an item that has not been given one draws its label and nothing
    // else, rather than a gap where a picture should be.
    val icon: VectorIcon? = null,
    // Set by the SERVER, because the server is what knows which screen it is building. A client
    // deciding this by comparing its current address against an action's payload would be a second
    // opinion about which tab is open, and the two would disagree the first time an address gained a
    // query parameter.
    val selected: Boolean = false,
)
