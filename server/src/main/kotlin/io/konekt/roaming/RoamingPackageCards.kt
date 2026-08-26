package io.konekt.roaming

import io.konekt.components.CounterStates
import io.konekt.components.UsageCounterCardComponent
import io.konekt.feature.roaming.server.data.RoamingDates
import io.konekt.feature.roaming.server.domain.RoamingPackage
import io.konekt.feature.usage.server.data.UsageUnits
import io.konekt.time.KonektClock

// A roaming package as the component that draws it.
//
// IT REUSES `UsageCounterCardComponent` rather than introducing a fourth card type, and that is a
// decision rather than laziness: the thing on screen is a title, an amount, a caption and a bar, which
// is exactly what that component is. A `RoamingPackageCardComponent` would be the same five fields
// under a different discriminator, and every client would need a renderer for it to draw the same
// card. What genuinely differs — a package that is full and not counting — is one word in `state`.
class RoamingPackageCards(
    private val clock: KonektClock,
) {
    fun of(pkg: RoamingPackage): UsageCounterCardComponent {
        val zone = RoamingZoneNames.of(pkg.zone)
        return UsageCounterCardComponent(
            id = idOf(pkg),
            title = "$zone data",
            valueText = UsageUnits.megabytes(pkg.remainingMb) + if (pkg.dormant) " ready" else " left",
            captionText = captionFor(pkg, zone),
            // A DORMANT PACKAGE DRAWS FULL, which is true: nothing has been spent. The bar is the one
            // part of this card that cannot express "has not started", which is what the state word
            // is for.
            progress = if (pkg.limitMb <= 0) 0f else pkg.remainingMb.toFloat() / pkg.limitMb.toFloat(),
            state =
                when {
                    pkg.dormant -> CounterStates.DORMANT
                    pkg.remainingMb <= 0 -> CounterStates.EXHAUSTED
                    pkg.remainingMb * LOW_DENOMINATOR <= pkg.limitMb -> CounterStates.LOW
                    else -> CounterStates.NORMAL
                },
        )
    }

    private fun captionFor(
        pkg: RoamingPackage,
        zone: String,
    ): String {
        // THE FIRST ACCEPTANCE CRITERION, in the only place a subscriber will ever read it. A dormant
        // package must not be silent: a card showing "10 GB ready" with no caption is one a subscriber
        // reasonably reads as already running, and then wonders why it never went down.
        val activatedAt =
            pkg.activatedAt ?: return "Starts when you first connect in $zone, then runs for ${days(pkg)}."

        val expiresAt = pkg.expiresAt
        return if (expiresAt == null) {
            // Started with no expiry is not a state this code can produce — both columns are written
            // by one statement. Saying the true half rather than inventing a date is what to do if it
            // ever becomes one.
            "Started ${RoamingDates.on(activatedAt)}."
        } else if (pkg.expiredAt(clock.now())) {
            "Ended ${RoamingDates.on(expiresAt)}."
        } else {
            // Dated from ACTIVATION, which is the second acceptance criterion as the subscriber sees
            // it: a package bought in March and started in June ends in July, not in April.
            "Started ${RoamingDates.on(activatedAt)}, ends ${RoamingDates.on(expiresAt)}."
        }
    }

    private fun days(pkg: RoamingPackage): String = if (pkg.validForDays == 1L) "a day" else "${pkg.validForDays} days"

    companion object {
        // A tenth left, the same threshold the usage counters use. One number, because a subscriber
        // who learns what amber means at home should not have to learn it again abroad.
        private const val LOW_DENOMINATOR = 10

        // Keyed on the package rather than generated, for the reason every component id here is: a
        // live update names the node it replaces, and a random id replaces nothing silently.
        fun idOf(pkg: RoamingPackage): String = "roaming-${pkg.id}"
    }
}
