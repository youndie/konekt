package io.konekt.feature.usage.server.data

import io.konekt.domain.Currency
import io.konekt.domain.Money
import io.konekt.feature.usage.server.domain.UsageAddOn
import io.konekt.feature.usage.server.domain.UsageAddOns
import io.konekt.feature.usage.server.domain.UsageCounter

// The top-up price list, in memory, for the same reason the plan catalogue is: the BSS is outside
// this system's boundary, and a table with a seed migration would be the same fiction with a schema
// around it.
//
// It exists because of one sentence on the canvas. The low state of a counter card reads "Minutes
// run out in about two days at your current pace. A 100-minute add-on costs $4." — a projection AND
// an offer. Without a price there is no offer, and the low state degrades into a warning, which is
// the version of that card a subscriber can do nothing about.
class StaticUsageAddOns(
    private val addOns: Map<UsageCounter.Kind, UsageAddOn> = DEFAULT,
) : UsageAddOns {
    override fun forKind(kind: UsageCounter.Kind): UsageAddOn? = addOns[kind]

    companion object {
        val DEFAULT =
            mapOf(
                UsageCounter.Kind.DATA to UsageAddOn(units = 1_024, price = Money.ofMajor(6, Currency.DEFAULT)),
                UsageCounter.Kind.MINUTES to UsageAddOn(units = 100, price = Money.ofMajor(4, Currency.DEFAULT)),
                UsageCounter.Kind.MESSAGES to UsageAddOn(units = 200, price = Money.ofMajor(2, Currency.DEFAULT)),
            )
    }
}
