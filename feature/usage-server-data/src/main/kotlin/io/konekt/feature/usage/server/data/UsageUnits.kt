package io.konekt.feature.usage.server.data

import io.konekt.feature.usage.server.domain.UsageCounter
import kotlin.math.roundToLong

// How an allowance is written for a person, on the server, because the server is the only side that
// formats (D15). A client that cannot format cannot format inconsistently, and there is no second
// copy of this in a view model dividing by a hard-coded thousand.
object UsageUnits {
    // "15.4 GB", "512 MB", "240 min". Data crosses into gigabytes because a subscriber does not read
    // "15360 MB" — the canvas writes the value in GB and it is right to.
    fun remaining(counter: UsageCounter): String =
        when (counter.kind) {
            UsageCounter.Kind.DATA -> data(counter.remainingUnits)
            UsageCounter.Kind.MINUTES -> "${grouped(counter.remainingUnits)} min"
            UsageCounter.Kind.MESSAGES -> "${grouped(counter.remainingUnits)} SMS"
        }

    // THE AMOUNT AS A NOUN — `100 min`, `200 SMS`, `1 GB` — for `Add 100 min for $4`; `size` below is
    // the adjective, `100-minute`, and the two read wrong in each other's place.
    fun amount(
        kind: UsageCounter.Kind,
        units: Long,
    ): String =
        when (kind) {
            UsageCounter.Kind.DATA -> data(units)
            UsageCounter.Kind.MINUTES -> "${grouped(units)} min"
            UsageCounter.Kind.MESSAGES -> "${grouped(units)} SMS"
        }

    fun size(
        kind: UsageCounter.Kind,
        units: Long,
    ): String =
        when (kind) {
            UsageCounter.Kind.DATA -> data(units)
            UsageCounter.Kind.MINUTES -> "${grouped(units)}-minute"
            UsageCounter.Kind.MESSAGES -> "${grouped(units)}-message"
        }

    // "less than a day", "about a day", "about two days", "about 12 days".
    //
    // Spelled up to ten and in digits after, which is how the canvas writes it and how prose reads.
    // The vagueness is deliberate and earned: the rate behind the number is a mean over the whole
    // allowance, so a figure like "1.8 days" would claim a precision the input does not have.
    fun approximately(days: Double): String =
        when {
            days < 1.0 -> {
                "less than a day"
            }

            days < 1.5 -> {
                "about a day"
            }

            else -> {
                val whole = days.roundToLong()
                if (whole == 1L) "about a day" else "about ${spelled(whole)} days"
            }
        }

    // The same rendering, for callers that hold a number of megabytes rather than a counter — the
    // roaming cards do. Public so there is ONE place that decides when a figure crosses into
    // gigabytes: two screens rounding differently is exactly the inconsistency D15 exists to prevent.
    fun megabytes(megabytes: Long): String = data(megabytes)

    private fun data(megabytes: Long): String =
        if (megabytes < MB_PER_GB) {
            "${grouped(megabytes)} MB"
        } else {
            val gigabytes = megabytes.toDouble() / MB_PER_GB
            val tenths = (gigabytes * 10).roundToLong()
            val whole = tenths / 10
            val fraction = tenths % 10
            // A whole number drops its zero fraction, the same rule MoneyFormat follows: "15 GB" and
            // not "15.0 GB".
            if (fraction == 0L) "${grouped(whole)} GB" else "${grouped(whole)}.$fraction GB"
        }

    // Commas every three digits, the American way, matching MoneyFormat. The product runs in USD and
    // a screen that groups money one way and megabytes another reads as two products.
    private fun grouped(value: Long): String =
        value
            .toString()
            .reversed()
            .chunked(3)
            .joinToString(",")
            .reversed()

    private fun spelled(value: Long): String =
        when (value) {
            2L -> "two"
            3L -> "three"
            4L -> "four"
            5L -> "five"
            6L -> "six"
            7L -> "seven"
            8L -> "eight"
            9L -> "nine"
            10L -> "ten"
            else -> value.toString()
        }

    private const val MB_PER_GB = 1_024L
}
