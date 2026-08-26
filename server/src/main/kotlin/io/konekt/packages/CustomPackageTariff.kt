package io.konekt.packages

import io.konekt.domain.Currency
import io.konekt.domain.Money

// THE PRICE, AND IT IS THE SERVER'S. A price computed on the client is a price a client can argue
// with, and the toolkit's readme says outright that limits and balances belong to the backend.
//
// One tariff function and no campaign layer: promotional pricing is named as not covered in B-20,
// because a discount that is a second function is a second thing to keep in step with this one.
object CustomPackageTariff {
    // The steps a subscriber may choose from. Discrete rather than continuous, because a tariff sells
    // packages rather than arbitrary quantities — and because the wire has no slider: kompot's
    // standard field set is text, amount, checkbox, autocomplete and selection, so a quantity is a
    // choice from a list.
    val DATA_GB_STEPS = listOf(0L, 1L, 5L, 10L, 20L, 50L)
    val MINUTES_STEPS = listOf(0L, 100L, 300L, 600L, 1_200L)
    val MESSAGES_STEPS = listOf(0L, 50L, 200L, 500L)

    // Minor units per unit of each quantity. Named constants rather than a table, because three lines
    // that a person reads are worth more here than a structure they have to decode — and this is the
    // one place a tariff change lands.
    private const val PER_GB_MINOR = 150L
    private const val PER_MINUTE_MINOR = 2L
    private const val PER_MESSAGE_MINOR = 1L

    // A package of nothing costs nothing, and it is a real state: the form opens on it.
    fun priceOf(
        dataGb: Long,
        minutes: Long,
        messages: Long,
    ): Money =
        Money(
            minorUnits = dataGb * PER_GB_MINOR + minutes * PER_MINUTE_MINOR + messages * PER_MESSAGE_MINOR,
            currency = Currency.DEFAULT,
        )

    // A quantity that is not one of the steps is refused rather than rounded. Rounding would charge a
    // subscriber for a package they did not choose, and the client picks from the same list — so a
    // value outside it arrived from something that is not the form.
    fun isStep(
        value: Long,
        steps: List<Long>,
    ): Boolean = value in steps
}
