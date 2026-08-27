package io.konekt.feature.purchase.server.domain

import io.konekt.domain.Money
import io.konekt.feature.roaming.server.domain.Zones
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ru.workinprogress.petich.PetichPayload
import ru.workinprogress.petich.PetichStatus

// What a purchase is, on the saga. @SerialName is load-bearing rather than cosmetic: without it the
// polymorphic discriminator is the fully qualified class name, which makes the STORAGE format depend
// on where the package lives — moving a module would render already-persisted sagas unreadable.
@Serializable
@SerialName("purchase")
data class PurchasePayload(
    val subscriberId: String,
    val accountId: String,
    val planId: String,
    val planTitle: String,
    val price: Money,
    // Recorded on the payload rather than re-read from the catalogue at EXECUTION time, for the same
    // reason the price is: the payload is what was AGREED, and a catalogue that moved between the
    // validation and the settlement would grant an allowance nobody was shown. Defaulted so sagas
    // written before this field decode — and they grant nothing, which is what they intended.
    val dataMb: Long = 0,
    // THE OTHER TWO ALLOWANCES, carried for the same reason and defaulted for the same one: a saga
    // persisted before they existed decodes and grants neither, which is what it meant.
    //
    // They exist because a counter kind that is never granted is a state nobody can reach. `Kind`
    // has known MINUTES and MESSAGES since the beginning and only DATA was ever handed out — so the
    // canvas's *Running low* and *Used up*, which it draws on minutes and SMS beside a healthy data
    // counter, were unreachable on any real account. The component could draw them; the product
    // could not produce them.
    val minutes: Long = 0,
    val messages: Long = 0,
    // Carried on the payload for the reason the price and the allowance are: the payload is what was
    // AGREED. A catalogue edited between the validation and the settlement must not change which
    // branch provisioning takes — a package sold as Turkey is provisioned as Turkey.
    //
    // Defaulted so sagas persisted before this field decode. They provision as home, which is what
    // they did when they were written.
    val zone: String = Zones.HOME,
    val validForDays: Long = 0,
) : PetichPayload()

const val PURCHASE_SAGA_TYPE = "purchase"

// What the subscriber must do for the saga to continue.
const val ACTION_CONFIRM = "CONFIRM"

// The order as the product speaks about it, derived from the saga's status.
//
// THE INTERESTING ROW IS THE LAST ONE. petich ends a cleanly rolled-back saga in `FAILED`, and
// showing a subscriber "failed" would be wrong twice over: nothing failed from their side, and the
// hold was reversed, which is the fact the screen exists to state. A compensation that itself failed
// does not reach `FAILED` — it stays `COMPENSATING` — so the word is unambiguous inside petich and
// merely unfortunate outside it.
enum class OrderStatus(
    val wireName: String,
) {
    PENDING("pending"),
    AWAITING_CONFIRMATION("awaiting_confirmation"),
    COMPLETED("completed"),

    // A `Reject`: a rule refused before anything happened, so there is nothing to reverse.
    REJECTED("rejected"),

    // Rolled back cleanly. petich calls this FAILED.
    COMPENSATED("compensated"),

    // In flight, or stuck because a compensating step itself failed — which is the one state that
    // needs a person, and the reason it is not folded into COMPENSATED.
    COMPENSATING("compensating"),
    ;

    companion object {
        fun of(status: PetichStatus): OrderStatus =
            when (status) {
                PetichStatus.DRAFT, PetichStatus.PROCESSING -> PENDING
                PetichStatus.PENDING_SIGNATURE -> AWAITING_CONFIRMATION
                PetichStatus.COMPLETED -> COMPLETED
                PetichStatus.REJECTED -> REJECTED
                PetichStatus.FAILED -> COMPENSATED
                PetichStatus.COMPENSATING -> COMPENSATING
            }
    }
}

data class Plan(
    val id: String,
    val title: String,
    val price: Money,
    val onSale: Boolean,
    // What the plan is made of, in the usage feature's own unit. A field rather than something read
    // out of `title`: "Turkey · 10 GB · 30 days" is copy, and parsing copy for a number is how a
    // renamed plan silently grants nothing.
    val dataMb: Long,
    // What the plan includes besides data. Zero on a roaming package, and the canvas says why: its
    // detail frame lists "Calls & SMS — not included". A data package is data.
    val minutes: Long = 0,
    val messages: Long = 0,
    // WHERE IT WORKS, and it is not decoration: every plan in the catalogue is a roaming package —
    // Turkey, Europe, the United States — and until this field existed they were all provisioned as
    // an ordinary home allowance that started counting the moment it was bought. A subscriber who
    // bought Turkey in March had spent it by April without leaving the country.
    //
    // Defaulted to HOME so a plan that genuinely is a home top-up needs to say nothing.
    val zone: String = Zones.HOME,
    // How many days it runs ONCE STARTED. Only meaningful for a roaming zone: a home allowance has no
    // start, so it has no expiry dated from one.
    val validForDays: Long = 0,
)

data class Entitlement(
    val id: String,
    val orderId: String,
    val subscriberId: String,
    val planId: String,
    val status: String,
) {
    companion object {
        const val PENDING = "pending"
        const val ACTIVE = "active"
        const val CANCELLED = "cancelled"
    }
}
