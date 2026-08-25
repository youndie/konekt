package io.konekt.feature.usage.server.data

import io.konekt.components.CounterStates
import io.konekt.components.UsageCounterCardComponent
import io.konekt.feature.usage.server.domain.UsageCounter

// A counter as the component that draws it.
//
// The id is DERIVED FROM THE COUNTER and not generated, because a live update names the component it
// replaces: `UpdateComponentMessage(componentId, component)` finds the node by that id in a tree the
// client already has. A random id would replace nothing, silently — the update arrives, the client
// looks for a node that is not there, and the screen simply does not change.
object UsageCounterCards {
    fun idOf(counter: UsageCounter): String = "counter-${counter.kind.wireName}"

    fun of(counter: UsageCounter): UsageCounterCardComponent =
        UsageCounterCardComponent(
            id = idOf(counter),
            title = counter.kind.title(),
            valueText = "${counter.remainingUnits} ${counter.kind.unit} left",
            // The copy CHANGES with the state, not only the colour. That is the canvas's rule and the
            // reason `state` is on the wire at all: a subscriber who is nearly out is told what to do
            // about it, and one who has run out is told plainly.
            captionText =
                when {
                    counter.isExhausted -> "You have used all of your ${counter.kind.title().lowercase()}."
                    counter.isLow -> "Running low — under a tenth of your ${counter.kind.title().lowercase()} is left."
                    else -> null
                },
            progress = counter.progress,
            state =
                when {
                    counter.isExhausted -> CounterStates.EXHAUSTED
                    counter.isLow -> CounterStates.LOW
                    else -> CounterStates.NORMAL
                },
        )

    private fun UsageCounter.Kind.title(): String =
        when (this) {
            UsageCounter.Kind.DATA -> "Data"
            UsageCounter.Kind.MINUTES -> "Minutes"
            UsageCounter.Kind.MESSAGES -> "Messages"
        }
}
