package io.konekt.components

import io.github.youndie.kompot.KompotAction
import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.KompotModifierNode
import io.github.youndie.kompot.registry.KompotComponentMarker
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// One line of history: a purchase, a top-up, an activation, or a reversal.
//
// `noteText` is the field this type exists for. A compensated order is a ROW, never an absence — the
// canvas draws it as "450 ₽ returned to balance on 28 Jun — profile never activated" — and a history
// that quietly omits what was undone is a history the subscriber cannot reconcile with their bank.
// The saga knows what it reversed; the note is where it says so.
@Serializable
@SerialName("order_row")
@KompotComponentMarker
data class OrderRowComponent(
    override val id: String,
    override val modifiers: List<KompotModifierNode> = emptyList(),
    // The short reference a subscriber quotes to support: "8f21-4c90".
    val reference: String,
    val title: String,
    val dateText: String,
    // Signed and formatted by the server: "+1 190 ₽", "−450 ₽".
    val amountText: String,
    // One of OrderStatuses. COMPENSATED is neither a success nor a failure and has its own word for
    // exactly that reason.
    val status: String,
    val statusText: String? = null,
    val noteText: String? = null,
    val action: KompotAction? = null,
) : KompotComponent
