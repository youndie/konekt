package io.konekt.money

import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Instant
import java.time.Instant as JavaInstant

// Dates become text on the server, for the same reason money does: the client renders a `text` and
// cannot get it wrong twice.
//
// ONE ZONE, the operator's, and it is a constant rather than a per-subscriber setting because this
// product does not know where a subscriber is. That is a real limitation and not a simplification —
// a traveller buying a roaming package at 23:00 their time may read a date a day off — and it is
// stated here rather than discovered. A per-subscriber zone is a column, a screen and a migration,
// and it belongs with the operator material rather than in a formatter.
object DayFormat {
    private val zone: ZoneId = ZoneId.of("UTC")

    // "28 Jun" — the canvas's form. No year, because every date this product shows is within weeks;
    // a date old enough to need one is a date this format is wrong for, and that is a change to make
    // when a screen needs it rather than a guess now.
    private val dayAndMonth = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

    fun dayAndMonth(instant: Instant): String =
        dayAndMonth.format(JavaInstant.ofEpochMilli(instant.toEpochMilliseconds()).atZone(zone))
}
