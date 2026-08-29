package io.konekt.feature.roaming.server.domain

import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

// WHERE A PACKAGE WORKS. A string rather than an enum, and the reason is the same one that keeps the
// usage counter's `kind` a string: the catalogue is data, and adding Japan must not be a code change
// in three modules and a migration with a lock on it.
//
// `HOME` is the absence of roaming rather than a zone anyone travels to. It is what a plan carries
// when it grants an ordinary allowance, and the value exists so that "which branch does provisioning
// take" is a comparison rather than a null check.
object Zones {
    const val HOME = "home"
}

// A package bought at home for a trip that has not happened yet.
//
// The two nullable fields are the whole feature. Everything else here is bookkeeping.
data class RoamingPackage(
    val id: String,
    val orderId: String,
    val subscriberId: String,
    val zone: String,
    val limitMb: Long,
    val remainingMb: Long,
    val validForDays: Long,
    val purchasedAt: Instant,
    // NULL until the first byte is used in the zone.
    val activatedAt: Instant?,
    // NULL until then too, and dated from activation rather than from purchase.
    val expiresAt: Instant?,
) {
    // BOUGHT AND NOT YET COUNTING — the first acceptance criterion, as a property rather than as a
    // condition each caller re-derives. Three screens ask this question, and three copies of
    // `activatedAt == null` is how one of them ends up asking `expiresAt == null` instead and being
    // subtly right until a package expires.
    val dormant: Boolean get() = activatedAt == null

    fun expiredAt(now: Instant): Boolean = expiresAt != null && expiresAt <= now

    // Usable means: not run out, and not past an expiry it has actually got. A dormant package cannot
    // be expired — that is the point of dating the expiry from activation.
    fun usableAt(now: Instant): Boolean = remainingMb > 0 && !expiredAt(now)

    // What the expiry BECOMES if this package is started now. Kept beside the field it computes so
    // the two cannot drift; the repository writes it and the screens preview it.
    fun expiryIfActivatedAt(now: Instant): Instant = now + validForDays.days
}

interface RoamingPackages {
    // Granted dormant, always. There is no overload that grants a started package, because there is
    // no moment at which buying one should start it — that is the feature.
    //
    // KEYED ON THE ORDER, so a saga that retries its EXECUTION step grants one package rather than
    // two. The unique constraint on order_id is what enforces it; this method must not fail on the
    // second call.
    suspend fun grant(
        orderId: String,
        subscriberId: String,
        zone: String,
        limitMb: Long,
        validForDays: Long,
        purchasedAt: Instant,
    )

    // The compensation half. Removes the package the given order granted, if it granted one.
    //
    // A HARD DELETE, unlike the allowance's clamped decrement, and it is safe for a reason worth
    // stating: a dormant package has been used by nobody, so there is nothing to preserve. A package
    // that HAS been started is a different matter — see the implementation.
    suspend fun revoke(orderId: String)

    suspend fun of(subscriberId: String): List<RoamingPackage>

    // Who is on a trip right now: a package that HAS started and has not ended, with its zone.
    //
    // THE SIMULATOR'S ONLY SOURCE, and the reason it is phrased this way rather than as "who owns a
    // roaming package". A simulator that ticked every package would start each one about five seconds
    // after it was bought, and the state this whole feature exists to make observable — bought, not
    // counting — would never be on screen long enough for anyone to see it. Starting a package is a
    // deliberate act; keeping a started one counting is the simulation.
    suspend fun travelling(): List<Travelling>

    // WHO HAS LANDED, in the simulation's terms: a package bought before `purchasedBefore` and still
    // dormant.
    //
    // A DELAY AND NOT AN EVENT, which is the whole of how `B-88` replaced the development route.
    // Arrival used to be a public POST — `/api/v1/dev/roaming/arrive`, taking the subscriber from the
    // QUERY rather than from a token, so wherever it was enabled anybody could start a stranger's
    // package and spend their allowance. It existed because the simulator deliberately would not
    // start a package: one that started itself five seconds after purchase makes the state this
    // feature is about — bought, not counting — unobservable.
    //
    // A cutoff answers both. The package stays dormant long enough to be seen, and then the
    // simulation flies the subscriber out; nothing outside the process decides it, and there is no
    // route to delete because there is no route.
    suspend fun awaitingArrival(purchasedBefore: Instant): List<Travelling>

    // Using data in a zone. THE ACTIVATION LIVES HERE rather than in a separate `activate` call, and
    // that is deliberate: "starts on first connection" means the start and the first consumption are
    // one event, and an API that lets them be two lets a caller do one without the other.
    suspend fun consume(
        subscriberId: String,
        zone: String,
        megabytes: Long,
        at: Instant,
    ): RoamingConsumption
}

sealed interface RoamingConsumption {
    // What was actually taken, and the package as it now stands. `consumedMb` can be less than what
    // was asked for: a package with 3 MB left absorbs 3 of a 10 MB request rather than going negative.
    data class Counted(
        val pkg: RoamingPackage,
        val consumedMb: Long,
        val started: Boolean,
    ) : RoamingConsumption

    // Nothing in this zone that could take it. The caller decides what that means — for the simulator
    // it is "you are roaming without a package", which is a real state and not an error.
    data object NoPackage : RoamingConsumption
}

data class Travelling(
    val subscriberId: String,
    val zone: String,
)
