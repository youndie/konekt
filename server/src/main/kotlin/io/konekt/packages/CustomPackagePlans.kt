package io.konekt.packages

import io.konekt.domain.KonektException
import io.konekt.feature.purchase.server.domain.Plan
import io.konekt.feature.purchase.server.domain.PlanCatalog
import io.konekt.feature.roaming.server.domain.Zones

// A CUSTOM PACKAGE IS A PLAN THE CATALOGUE DID NOT WRITE DOWN.
//
// `B-87` needed the builder's three quantities to become an order, and the purchase saga takes a plan
// id and re-resolves it from the catalogue at every step. Two ways to do that: teach the saga about a
// second kind of thing to sell, or make the catalogue able to answer for a package it never listed.
// The second is smaller and truer — the interceptors do not need to know the difference, and every
// refusal, compensation and screen the purchase already has works unchanged.
//
// THE ID CARRIES THE QUANTITIES and nothing else — `custom-10-300-50`. No row is written, because
// there is nothing to write: the package IS its three numbers, and a table would be a second place
// for them to live between the form and the order.
//
// WHICH MEANS THE ID IS UNTRUSTED INPUT, and that is the whole reason `find` re-validates the steps
// rather than parsing three numbers. An id is a string a caller can invent, so `custom-9999-0-0`
// arrives at this function exactly as a real one does; answering `null` is what makes it a 404 rather
// than a package nobody was offered. The price is never in the id — it is computed here, from the
// same tariff function the form prices with, so a client cannot name one.
class CustomPackagePlans(
    private val catalogue: PlanCatalog,
) : PlanCatalog {
    override suspend fun find(planId: String): Plan? = parse(planId) ?: catalogue.find(planId)

    // THE LISTED PLANS AND NOTHING ELSE. A custom package has no place in the catalogue screen: there
    // are as many of them as there are combinations, and none of them exists until somebody builds it.
    override suspend fun all(): List<Plan> = catalogue.all()

    private fun parse(planId: String): Plan? {
        val parts = planId.removePrefix("$PREFIX-").split("-")
        if (!planId.startsWith("$PREFIX-") || parts.size != 3) return null

        val dataGb = parts[0].toLongOrNull() ?: return null
        val minutes = parts[1].toLongOrNull() ?: return null
        val messages = parts[2].toLongOrNull() ?: return null

        // The same three lists the form offers and the patch prices against. A quantity outside them
        // did not come from the builder.
        if (!CustomPackageTariff.isStep(dataGb, CustomPackageTariff.DATA_GB_STEPS)) return null
        if (!CustomPackageTariff.isStep(minutes, CustomPackageTariff.MINUTES_STEPS)) return null
        if (!CustomPackageTariff.isStep(messages, CustomPackageTariff.MESSAGES_STEPS)) return null

        return Plan(
            id = planId,
            title = titleOf(dataGb, minutes, messages),
            price = CustomPackageTariff.priceOf(dataGb, minutes, messages),
            onSale = true,
            dataMb = dataGb * MB_PER_GB,
            minutes = minutes,
            messages = messages,
            // A HOME package. The builder sells an allowance for where a subscriber lives; a roaming
            // one is dormant until arrival, which is a different product and one the catalogue lists.
            zone = Zones.HOME,
        )
    }

    // What the order history calls it three months later, when there is no card under the row to
    // carry the rest. Composed here for the same reason every other string is composed on the server.
    private fun titleOf(
        dataGb: Long,
        minutes: Long,
        messages: Long,
    ): String =
        buildList {
            if (dataGb > 0) add("$dataGb GB")
            if (minutes > 0) add("$minutes min")
            if (messages > 0) add("$messages SMS")
        }.joinToString(" · ", prefix = "Your package · ")

    companion object {
        const val PREFIX = "custom"

        // The same base `UsageUnits` writes "20 GB" with. Two figures on one screen computed in two
        // bases would disagree with each other for a living.
        private const val MB_PER_GB = 1_024L

        // THE ID, BUILT ONLY HERE AND ONLY FROM VALIDATED QUANTITIES. The submit route calls this
        // after checking the steps; nothing else composes one, so there is one spelling of the format
        // rather than one per caller.
        fun idOf(
            dataGb: Long,
            minutes: Long,
            messages: Long,
        ): String = "$PREFIX-$dataGb-$minutes-$messages"

        // A PACKAGE OF NOTHING IS A REAL STATE AND NOT AN ORDER. The form opens on it — three zeros,
        // priced at nothing — and it must stay openable, so the refusal belongs at the submit rather
        // than in the tariff or the parser.
        fun requireSomethingChosen(quantities: CustomPackageQuantities) {
            if (quantities.dataGb == 0L && quantities.minutes == 0L && quantities.messages == 0L) {
                throw KonektException.Validation(
                    "custom-package",
                    "Choose some data, minutes or messages before ordering.",
                )
            }
        }
    }
}
