package io.konekt.tariff

import io.konekt.domain.Money
import io.konekt.time.KonektClock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ru.workinprogress.petich.PetichPayload
import java.time.ZoneId
import kotlin.time.Instant
import java.time.Instant as JavaInstant

// What a tariff is here: a name, a monthly price and the allowance it carries. In memory, because the
// BSS is outside this system's boundary — the same reason `StaticPlanCatalog` is.
data class Tariff(
    val id: String,
    val title: String,
    val monthlyPrice: Money,
    val dataMb: Long,
)

const val TARIFF_CHANGE_SAGA_TYPE = "tariff_change"

// What a tariff change is, on the saga. @SerialName for the load-bearing reason the other payloads
// carry: without it the polymorphic discriminator is the fully qualified class name, and the STORAGE
// format of every persisted saga then depends on where this package lives.
@Serializable
@SerialName("tariff_change")
data class TariffChangePayload(
    val subscriberId: String,
    val fromTariffId: String,
    val toTariffId: String,
    // Decided when the change is REQUESTED rather than when it is applied. A subscriber who is told
    // "from the first of next month" and confirms on the thirty-first must get the date they were
    // shown, not the one the clock produces a minute later.
    val effectiveAt: Long,
) : PetichPayload()

// THE BOUNDARY, and it is a boundary rather than "now" on purpose. An immediate change makes
// proration the centre of the feature, and proration is arithmetic this build has nothing to say
// about — B-21 says so and rejects the alternative outright.
//
// The first of the next month, in UTC. A real operator's cycle is per-subscriber and this one is not;
// what matters for the demonstration is that the date is a boundary, that the server decides it, and
// that the screen says which.
object BillingBoundary {
    // The operator's zone, the same constant `DayFormat` uses and for the same stated reason: this
    // product does not know where a subscriber is. A boundary computed in one zone and printed in
    // another would be a date off by a day for half the world, so both come from here.
    private val zone: ZoneId = ZoneId.of("UTC")

    // `java.time` rather than arithmetic over day counts. The first version of this walked days and
    // counted months by hand — correct, and a calendar implementation nobody asked for living beside
    // one the JDK already ships. `:server` is JVM by construction (Exposed publishes no common
    // metadata), so there is nothing multiplatform to preserve here.
    fun nextAfter(clock: KonektClock): Instant =
        JavaInstant
            .ofEpochMilli(clock.now().toEpochMilliseconds())
            .atZone(zone)
            .toLocalDate()
            .withDayOfMonth(1)
            .plusMonths(1)
            .atStartOfDay(zone)
            .toInstant()
            .let { Instant.fromEpochMilliseconds(it.toEpochMilli()) }
}
