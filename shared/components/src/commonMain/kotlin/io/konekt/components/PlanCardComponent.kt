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
    // WHAT A UNIT OF IT COSTS — "$1.20 / GB" — drawn under the price, which is where the canvas puts
    // it and why it is a field of its own rather than another `quotaText`. Its whole job is to be
    // read against the price above it.
    //
    // Formatted by the server like every other amount in this product (D15), and NULLABLE because a
    // plan can carry nothing to divide by: a top-up of minutes has no gigabytes, and a card that
    // said "$0.00 / GB" would be answering a question nobody asked.
    val perUnitText: String? = null,
    // "Works in Turkey" — where the plan applies, as a line under the title.
    //
    // NOTHING SENDS IT SINCE `B-57`, and it is kept rather than removed. The card's title is the
    // place now — "Turkey", the way the canvas draws it — so a line saying where it works repeats the
    // heading. The field stays because taking a name off the wire is a coordinated release of both
    // sides for a screen nobody is asking to change, and because a deployment whose plans are not
    // organised by place would want exactly this line back.
    val zoneText: String? = null,
    // "Popular", "Best value". Purely a marketing label, and separate from `state` because the two
    // are independent: a sold-out plan may still be the popular one.
    val badgeText: String? = null,
    // One of PlanStates. LOADING is a real wire state, not a client flag: a row still being priced is
    // something the server knows and the client cannot guess.
    val state: String = PlanStates.AVAILABLE,
    val action: KompotAction? = null,
    // THE PILL'S WORD (`B-114`, block 4): `Choose` on the right of the card, pressing the same action
    // the card does. Absent on a card that cannot be chosen, and ignored by a client that predates it
    // — the card was the whole press target before, and still is.
    val actionText: String? = null,
) : KompotComponent
