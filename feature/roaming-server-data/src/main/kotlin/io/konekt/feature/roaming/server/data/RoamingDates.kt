package io.konekt.feature.roaming.server.data

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

// "14 June". A date as a person reads it, formatted on the server because the server is the only side
// that formats (D15).
//
// UTC RATHER THAN THE SUBSCRIBER'S ZONE, and it is worth being honest about why: this product has no
// notion of where a subscriber is. Picking the device's zone would be a client-side format, which D15
// forbids; picking the roaming zone's would be wrong for the half of the trip spent packing. UTC is
// the one choice that is consistently one thing, and a package whose end date reads a day off in
// Auckland is a smaller defect than two screens disagreeing about it.
object RoamingDates {
    private val months =
        listOf(
            "January",
            "February",
            "March",
            "April",
            "May",
            "June",
            "July",
            "August",
            "September",
            "October",
            "November",
            "December",
        )

    fun on(instant: Instant): String {
        val date = instant.toLocalDateTime(TimeZone.UTC).date
        return "${date.day} ${months[date.month.ordinal]}"
    }
}
