package io.konekt.roaming

import io.konekt.feature.roaming.server.domain.RoamingConsumption
import io.konekt.feature.roaming.server.domain.RoamingPackage
import io.konekt.feature.roaming.server.domain.RoamingPackages
import io.konekt.feature.roaming.server.domain.Travelling
import io.konekt.time.KonektClock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

// THE ORDER THE TRAVEL SCREEN PUTS ZONES IN, and until `B-96` moved it out of the renderer there was
// no test of it anywhere: the rule lived in a private comparator inside `RoamingScreen`, and the only
// way to ask it a question was to build a component tree and read the ids of the headings out of it.
//
// It is a rule about TIME — a package is running, or waiting, or over — which is what makes the
// second half of this file possible: one `now`, taken once, for every question the response answers.
class ViewRoamingUseCaseTest {
    private val now = Instant.fromEpochMilliseconds(1_760_000_000_000)

    private fun pkg(
        id: String,
        zone: String,
        activatedAt: Instant? = null,
        expiresAt: Instant? = null,
        remainingMb: Long = 4_096,
    ) = RoamingPackage(
        id = id,
        orderId = "ord-$id",
        subscriberId = "sub-1",
        zone = zone,
        limitMb = 10_240,
        remainingMb = remainingMb,
        validForDays = 30,
        purchasedAt = now - 10.days,
        activatedAt = activatedAt,
        expiresAt = expiresAt,
    )

    private fun heldBy(packages: List<RoamingPackage>) =
        object : RoamingPackages {
            override suspend fun of(subscriberId: String): List<RoamingPackage> = packages

            override suspend fun grant(
                orderId: String,
                subscriberId: String,
                zone: String,
                limitMb: Long,
                validForDays: Long,
                purchasedAt: Instant,
            ) = TODO("not part of this screen")

            override suspend fun revoke(orderId: String) = TODO("not part of this screen")

            override suspend fun travelling(): List<Travelling> = TODO("not part of this screen")

            override suspend fun awaitingArrival(purchasedBefore: Instant): List<Travelling> =
                TODO("not part of this screen")

            override suspend fun consume(
                subscriberId: String,
                zone: String,
                megabytes: Long,
                at: Instant,
            ): RoamingConsumption = TODO("not part of this screen")
        }

    private fun useCase(held: List<RoamingPackage>) = ViewRoamingUseCase(heldBy(held), KonektClock { now })

    // THE WHOLE ORDERING, as a table. Running, then waiting, then over — the order of a subscriber's
    // attention, and the reason a package bought for next month must not sit above the one counting
    // down right now.
    @Test
    fun `zones are ordered by how urgent their most urgent package is`() =
        runTest {
            val view =
                useCase(
                    listOf(
                        pkg("over", "us", activatedAt = now - 40.days, expiresAt = now - 10.days),
                        pkg("waiting", "eu"),
                        pkg("running", "tr", activatedAt = now - 1.days, expiresAt = now + 29.days),
                    ),
                ).invoke("sub-1").getOrThrow()

            assertEquals(listOf("tr", "eu", "us"), view.zones.map { it.zone })
        }

    // A ZONE IS AS URGENT AS ITS MOST URGENT PACKAGE, which is the half a per-package sort would get
    // wrong: a zone holding one running package and one that ended belongs at the top, not in the
    // middle of both.
    @Test
    fun `a zone with something running outranks one that only has something waiting`() =
        runTest {
            val view =
                useCase(
                    listOf(
                        pkg("waiting", "eu"),
                        pkg("ended", "tr", activatedAt = now - 40.days, expiresAt = now - 10.days),
                        pkg("running", "tr", activatedAt = now - 1.days, expiresAt = now + 29.days),
                    ),
                ).invoke("sub-1").getOrThrow()

            assertEquals(listOf("tr", "eu"), view.zones.map { it.zone })
        }

    // THE NAME IS RESOLVED HERE, so the screen draws a heading rather than looking a code up. The
    // fallback is the code itself for the reason `RoamingZoneNames` gives: a zone added to the
    // catalogue and not to the map must draw a heading that works, not a 500.
    @Test
    fun `a zone arrives with the name a person reads`() =
        runTest {
            val view = useCase(listOf(pkg("p", "tr"), pkg("q", "zz"))).invoke("sub-1").getOrThrow()

            assertEquals("Turkey", view.zones.single { it.zone == "tr" }.title)
            assertEquals("zz", view.zones.single { it.zone == "zz" }.title)
        }

    // WITHIN A ZONE, THE ORDER THEY WILL BE SPENT IN — which is the order the repository returns, and
    // the order `consume` actually uses. A screen that sorted them any other way would disagree with
    // the meter.
    @Test
    fun `packages keep the order the repository gave them`() =
        runTest {
            val first = pkg("first", "tr", activatedAt = now - 1.days, expiresAt = now + 29.days)
            val second = pkg("second", "tr")
            val view = useCase(listOf(first, second)).invoke("sub-1").getOrThrow()

            assertEquals(
                listOf("first", "second"),
                view.zones
                    .single()
                    .packages
                    .map { it.id },
            )
        }

    // ONE `now` FOR THE WHOLE ANSWER, and it travels with it. The screen used to rank the zones
    // against one reading of the clock while every card captioned itself against another — invisible
    // in a test and not impossible in production, because a response is not instantaneous.
    //
    // A clock that MOVES ON EVERY READ is what proves it: the view is built from exactly one tick, so
    // whatever the cards are later given, it is the instant the ranking used.
    //
    // TWO ZONES AND NOT ONE, and that is not decoration. Written with a single zone this test passed
    // against a `rankOf(inZone, clock.now())` that reads the clock per comparison — because a
    // `sortedBy` over a one-element list never calls its selector, so the extra read never happened.
    // A test whose subject is "how many times is this called" must give it something to call it for.
    @Test
    fun `the whole answer is decided against a single instant`() =
        runTest {
            var reads = 0
            val view =
                ViewRoamingUseCase(
                    heldBy(
                        listOf(
                            pkg("running", "tr", activatedAt = now - 1.days, expiresAt = now + 29.days),
                            pkg("waiting", "eu"),
                        ),
                    ),
                    KonektClock { now + (reads++).days },
                )("sub-1").getOrThrow()

            assertEquals(1, reads, "the answer was decided against $reads different instants")
            assertTrue(view.at == now, "the instant the view carries is not the one it was built from")
            // And the ordering that single instant produced is still the right one — a test that only
            // counted reads would pass on a use case that took one reading and ranked nothing.
            assertEquals(listOf("tr", "eu"), view.zones.map { it.zone })
        }

    // AN EMPTY LINE IS AN EMPTY LIST OF ZONES, not an absent view. The screen has an empty state to
    // draw — `B-88`'s whole point, and the state the home banner had closed the only door to.
    @Test
    fun `a line with no package has no zones rather than no view`() =
        runTest {
            assertEquals(emptyList(), useCase(emptyList()).invoke("sub-1").getOrThrow().zones)
        }
}
