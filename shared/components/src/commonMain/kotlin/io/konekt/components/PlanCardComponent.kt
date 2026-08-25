package io.konekt.components

import io.github.youndie.kompot.KompotAction
import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.KompotModifierNode
import io.github.youndie.kompot.registry.KompotComponentMarker
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// One plan or package in the catalogue, and the same type on its detail screen.
//
// The quota triple travels as a list of ready strings rather than three named fields, and that is
// the decision worth explaining: a plan is sometimes "10 GB · 30 days" and sometimes
// "10 GB · 300 min · 50 SMS · 30 days", and a fixed shape would have to carry nulls for a plan that
// simply has no minutes. A list of what this plan actually offers says the same thing without
// inventing an absence.
@Serializable
@SerialName("plan_card")
@KompotComponentMarker
data class PlanCardComponent(
    override val id: String,
    override val modifiers: List<KompotModifierNode> = emptyList(),
    val title: String,
    // Already formatted, with its currency. See the note on UsageCounterCardComponent — the server
    // is the only side that formats money in this product.
    val priceText: String,
    val quotaTexts: List<String> = emptyList(),
    // "Turkey", "Home" — where the plan applies. Absent for a plan that applies everywhere the
    // subscriber already is.
    val zoneText: String? = null,
    // "Popular", "Best value". Purely a marketing label, and separate from `state` because the two
    // are independent: a sold-out plan may still be the popular one.
    val badgeText: String? = null,
    // One of PlanStates. LOADING is a real wire state, not a client flag: a row still being priced is
    // something the server knows and the client cannot guess.
    val state: String = PlanStates.AVAILABLE,
    val action: KompotAction? = null,
) : KompotComponent
