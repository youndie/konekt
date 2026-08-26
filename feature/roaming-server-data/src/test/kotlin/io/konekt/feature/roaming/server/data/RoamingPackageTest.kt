package io.konekt.feature.roaming.server.data

import io.konekt.db.tables.SubscriberTable
import io.konekt.feature.roaming.server.domain.RoamingConsumption
import io.konekt.testing.PostgresHarness
import io.konekt.time.KonektClock
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

// B-19'S TWO ACCEPTANCE CRITERIA, against a real Postgres.
//
// Against a real one because both of them are claims about columns: "not yet counting" is
// `activated_at IS NULL`, and "its expiry is dated from that moment" is a value written by an UPDATE.
// A fake repository would let this file assert whatever the fake was written to do — which is the
// shape of test that passes on the day the schema stops agreeing with it.
@OptIn(ExperimentalUuidApi::class)
class RoamingPackageTest {
    // Bought in March. Every instant below is offset from this one, so the dates in the assertions are
    // arithmetic rather than magic numbers copied out of a failure message.
    private val boughtAt = Instant.parse("2026-03-01T09:00:00Z")

    private var now = boughtAt
    private val clock = KonektClock { now }
    private val packages = ExposedRoamingPackages(PostgresHarness.database, clock)
    private lateinit var subscriberId: String

    @BeforeTest
    fun seed() {
        PostgresHarness.truncateAll()
        now = boughtAt
        val id = Uuid.random().toString()
        subscriberId = id
        transaction(PostgresHarness.database) {
            SubscriberTable.insert {
                it[SubscriberTable.id] = id
                it[msisdn] = "1555010${(1000..9999).random()}"
                it[createdAt] = 0
            }
        }
    }

    private suspend fun buyTurkey(orderId: String = Uuid.random().toString()): String {
        packages.grant(
            orderId = orderId,
            subscriberId = subscriberId,
            zone = "tr",
            limitMb = 10_240,
            validForDays = 30,
            purchasedAt = boughtAt,
        )
        return orderId
    }

    // AC1: "a roaming package bought at home shows as bought and not yet counting."
    @Test
    fun `a package bought at home is dormant and full`(): Unit =
        runBlocking {
            buyTurkey()

            val pkg = assertNotNull(packages.of(subscriberId).singleOrNull())
            assertTrue(pkg.dormant, "a package bought at home must not have started")
            assertNull(pkg.activatedAt)
            // The half that is easy to get wrong: an expiry written at purchase would make a package
            // bought for June expire in April, and nothing about the package being full would say so.
            assertNull(pkg.expiresAt, "a package that has not started cannot have an end date")
            assertEquals(10_240, pkg.remainingMb)
        }

    // AC1's other half, and the one a passing repository can still fail: time moving must not start
    // it. This is what separates "dormant" from "not looked at yet".
    @Test
    fun `three months at home do not start it or expire it`(): Unit =
        runBlocking {
            buyTurkey()
            now = boughtAt + 90.days

            val pkg = assertNotNull(packages.of(subscriberId).singleOrNull())
            assertTrue(pkg.dormant)
            assertTrue(pkg.usableAt(now), "a 30-day package bought 90 days ago has still not started")
            // And the simulator must not be ticking it: nobody is on a trip.
            assertEquals(emptyList(), packages.travelling())
        }

    // AC2: "the same package after simulated first use counts down and its expiry is dated from that
    // moment."
    @Test
    fun `first use starts it and dates the expiry from then`(): Unit =
        runBlocking {
            buyTurkey()

            // June, not March. The gap is the point of the test.
            val landedAt = boughtAt + 100.days
            now = landedAt

            val result = packages.consume(subscriberId, "tr", megabytes = 40, at = landedAt)

            val counted = assertIs<RoamingConsumption.Counted>(result)
            assertTrue(counted.started, "the first use of a dormant package must start it")
            assertEquals(40, counted.consumedMb)
            assertEquals(10_200, counted.pkg.remainingMb, "it counts down")

            val stored = assertNotNull(packages.of(subscriberId).singleOrNull())
            assertEquals(landedAt, stored.activatedAt)
            // THE CRITERION ITSELF. 30 days from the landing, not from the purchase — which would be
            // 30 days after March and therefore already past.
            assertEquals(landedAt + 30.days, stored.expiresAt)
            assertTrue(stored.expiresAt!! > now, "a package started today cannot already have ended")
        }

    @Test
    fun `a started package is ticked by the simulator and a second use does not restart it`(): Unit =
        runBlocking {
            buyTurkey()
            val landedAt = boughtAt + 100.days
            now = landedAt
            packages.consume(subscriberId, "tr", megabytes = 40, at = landedAt)

            // Now — and only now — the simulator has something to tick.
            assertEquals(listOf(subscriberId to "tr"), packages.travelling().map { it.subscriberId to it.zone })

            val later = landedAt + 1.days
            now = later
            val second =
                assertIs<RoamingConsumption.Counted>(packages.consume(subscriberId, "tr", megabytes = 60, at = later))

            assertFalse(second.started, "a package that has already started must not start again")
            // The expiry stays where the FIRST use put it. A clock restarted by every session is a
            // package that never ends.
            assertEquals(landedAt + 30.days, second.pkg.expiresAt)
            assertEquals(10_140, second.pkg.remainingMb)
        }

    @Test
    fun `an expired package is neither spent nor ticked`(): Unit =
        runBlocking {
            buyTurkey()
            val landedAt = boughtAt + 100.days
            now = landedAt
            packages.consume(subscriberId, "tr", megabytes = 40, at = landedAt)

            now = landedAt + 31.days
            assertEquals(emptyList(), packages.travelling(), "an ended trip must not keep ticking")
            assertEquals(RoamingConsumption.NoPackage, packages.consume(subscriberId, "tr", 10, now))
        }

    // The clamp the usage counters learned the expensive way, at the same boundary: a request taking
    // more than half of what is left must leave the rest, not zero it and not go negative.
    @Test
    fun `a use larger than the remainder takes only the remainder`(): Unit =
        runBlocking {
            buyTurkey()
            val landedAt = boughtAt + 100.days
            now = landedAt
            packages.consume(subscriberId, "tr", megabytes = 10_200, at = landedAt)

            val result =
                assertIs<RoamingConsumption.Counted>(
                    packages.consume(subscriberId, "tr", megabytes = 500, at = landedAt),
                )
            assertEquals(40, result.consumedMb, "it can only spend what is left")
            assertEquals(0, result.pkg.remainingMb, "and never less than nothing")
        }

    @Test
    fun `a zone with nothing bought for it spends nothing`(): Unit =
        runBlocking {
            buyTurkey()
            assertEquals(RoamingConsumption.NoPackage, packages.consume(subscriberId, "us", 40, now))
        }

    // The saga's idempotence, at the level that enforces it. A retried EXECUTION step must grant one
    // package rather than two — and a compensation must remove exactly what its order granted.
    @Test
    fun `granting twice for one order grants one package`(): Unit =
        runBlocking {
            val orderId = buyTurkey()
            buyTurkey(orderId)
            assertEquals(1, packages.of(subscriberId).size)
        }

    @Test
    fun `a rollback removes a dormant package and keeps a started one`(): Unit =
        runBlocking {
            val dormant = buyTurkey()
            packages.revoke(dormant)
            assertEquals(emptyList(), packages.of(subscriberId))

            val started = buyTurkey()
            now = boughtAt + 100.days
            packages.consume(subscriberId, "tr", megabytes = 40, at = now)
            packages.revoke(started)
            // Spent bytes are not something a compensation may erase — see `revoke`.
            assertEquals(1, packages.of(subscriberId).size)
        }
}
