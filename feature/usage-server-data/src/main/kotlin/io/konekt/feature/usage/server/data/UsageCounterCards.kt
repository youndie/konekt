package io.konekt.feature.usage.server.data

import io.konekt.components.CounterStates
import io.konekt.components.UsageCounterCardComponent
import io.konekt.feature.usage.server.domain.UsageAddOns
import io.konekt.feature.usage.server.domain.UsageCounter
import io.konekt.money.MoneyFormat
import kotlin.time.Instant

// A counter as the component that draws it.
//
// The id is DERIVED FROM THE COUNTER and not generated, because a live update names the component it
// replaces: `UpdateComponentMessage(componentId, component)` finds the node by that id in a tree the
// client already has. A random id would replace nothing, silently — the update arrives, the client
// looks for a node that is not there, and the screen simply does not change.
//
// A class rather than an object since the copy became a projection: the caption needs the time and a
// price list, and reaching for either through a global is how a screen becomes untestable without
// waiting for tomorrow.
// THE INSTANT IS AN ARGUMENT AND NOT A CLOCK OF ITS OWN (`B-96`). A screen draws several of these
// and reads the time once for all of them; a factory holding a clock reads it per card, so one
// response could caption two counters against two different `now`s. It costs a parameter and it makes
// that impossible rather than unlikely.
class UsageCounterCards(
    private val addOns: UsageAddOns,
) {
    fun of(
        counter: UsageCounter,
        at: Instant,
    ): UsageCounterCardComponent =
        UsageCounterCardComponent(
            id = idOf(counter),
            title = counter.kind.title(),
            valueText = "${UsageUnits.remaining(counter)} left",
            // THE COPY CHANGES WITH THE STATE, not only the colour. That is the canvas's rule and the
            // reason `state` is on the wire at all: a subscriber who is nearly out is told when they
            // will run out and what it costs to fix, and one who has run out is told plainly.
            captionText = captionFor(counter, at),
            progress = counter.progress,
            state =
                when {
                    counter.isExhausted -> CounterStates.EXHAUSTED
                    counter.isLow -> CounterStates.LOW
                    else -> CounterStates.NORMAL
                },
        )

    private fun captionFor(
        counter: UsageCounter,
        at: Instant,
    ): String? {
        // Nothing to say in the ordinary state, and saying something anyway is how a caption stops
        // being read by the time it matters.
        if (!counter.isLow && !counter.isExhausted) return null

        val offer = offerFor(counter)

        if (counter.isExhausted) {
            return listOfNotNull("You have used all of your ${counter.kind.title().lowercase()}.", offer)
                .joinToString(" ")
        }

        // The projection, when there is one. It is absent for an allowance nothing has been spent
        // from yet and for one granted moments ago — and in those cases the card falls back to the
        // fact rather than inventing a date, because "runs out today" is what a naive zero would say.
        val running =
            counter
                .daysRemaining(at)
                ?.let { "${counter.kind.runsOut()} in ${UsageUnits.approximately(it)} at your current pace." }
                ?: "Running low — under a tenth of your ${counter.kind.title().lowercase()} is left."

        return listOfNotNull(running, offer).joinToString(" ")
    }

    // "A 100-minute add-on costs $4." Absent when nothing is sold for that counter, which leaves the
    // card a warning rather than an offer — worse, and honest.
    private fun offerFor(counter: UsageCounter): String? =
        addOns.forKind(counter.kind)?.let { addOn ->
            "A ${UsageUnits.size(counter.kind, addOn.units)} add-on costs ${MoneyFormat.format(addOn.price)}."
        }

    // "Data runs out", "Minutes run out". The verb follows the noun's number, and getting it wrong
    // is the kind of thing that reads as a machine wrote the screen — which, on a backend-driven
    // product, is exactly the impression the copy is there to avoid.
    private fun UsageCounter.Kind.runsOut(): String =
        when (this) {
            UsageCounter.Kind.DATA -> "Data runs out"
            UsageCounter.Kind.MINUTES -> "Minutes run out"
            UsageCounter.Kind.MESSAGES -> "Messages run out"
        }

    private fun UsageCounter.Kind.title(): String =
        when (this) {
            UsageCounter.Kind.DATA -> "Data"
            UsageCounter.Kind.MINUTES -> "Minutes"
            UsageCounter.Kind.MESSAGES -> "Messages"
        }

    companion object {
        fun idOf(counter: UsageCounter): String = "counter-${counter.kind.wireName}"
    }
}
