package io.konekt.feature.purchase.shared.api

import io.github.youndie.kompot.KompotAction
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

// THE PRODUCT'S CENTRAL VERB, and it needed an action of its own.
//
// Everything else on a screen is a transition: `navigate` moves, and the server decides where. Buying
// is not a transition — it creates something, and where the subscriber goes next depends on what was
// created. A `navigate` whose destination the server guessed in advance would be a lie the first time
// a purchase was refused.
//
// WHY NOT `kompot-commands`' `perform`. That action posts to a URL and feeds the answer back into the
// handler chain, which is the general version of this and the right one for a toolkit. Taking it
// means a dependency, a `submit` endpoint answering a `KompotAction`, and the conformance kit's
// perform check — worth doing when a second verb needs it, and one verb does not make a vocabulary.
// Recorded here so the next one is a decision rather than a second special case.
//
// IT CARRIES A PLAN ID AND NOTHING ELSE. Not a price: a price on the wire is a price a client could
// send back, and the whole reason the purchase payload records what was AGREED is that the catalogue
// is the server's. The client names what was chosen; the server decides what it costs.
@Serializable
@SerialName("buy_plan")
data class BuyPlanAction(
    val planId: String,
) : KompotAction

// THE OTHER HALF OF BUYING, and its absence made the first half useless.
//
// The purchase saga SUSPENDS at the confirmation: `requiredAction = "CONFIRM"`, and the order waits
// until somebody posts to it. The screen a subscriber lands on offered nothing to press, so the one
// action they must take was reachable from a terminal and from nowhere in the product — every
// purchase made through the application expired and rolled back.
//
// It carries the order id and nothing else, for the same reason `buy_plan` carries a plan id: what
// is being confirmed is the client's to name, and everything about what it costs and whether it may
// still be confirmed is the server's to decide.
@Serializable
@SerialName("confirm_purchase")
data class ConfirmPurchaseAction(
    val orderId: String,
) : KompotAction

// Registered by hand, the way `EsimWizardStepAction` is and for the reason its comment gives: actions
// are not generated, so nothing fails at build time if this is left out of an application's `Json`.
// The action simply cannot be decoded, at runtime, on the one press that matters.
val purchaseActionsSerializersModule =
    SerializersModule {
        polymorphic(KompotAction::class) {
            subclass(BuyPlanAction::class)
            subclass(ConfirmPurchaseAction::class)
        }
    }
