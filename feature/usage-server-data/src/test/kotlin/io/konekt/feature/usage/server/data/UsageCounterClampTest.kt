package io.konekt.feature.usage.server.data

import io.konekt.db.tables.SubscriberTable
import io.konekt.feature.usage.server.domain.UsageCounter
import io.konekt.testing.PostgresHarness
import io.konekt.time.KonektClock
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

// THE BOUNDARY BOTH CLAMPS USED TO GET WRONG, and the reason this file exists at all.
//
// `consume` and `revokePlanAllowance` each apply a subtract and a clamp as two statements. Written
// subtract-first they are not mutually exclusive: the subtract changes the row, and the clamp's
// predicate then reads the NEW value. So every amount in `[units, 2 * units)` — that is, every
// consumption taking more than half of what was left — subtracted correctly and was then zeroed.
//
// It never produced a negative number, never threw, and never logged. What it produced was a
// subscriber whose remaining allowance had silently become zero. The one test that would have caught
// it had been written and could not run: its expression body ended in `assertNotNull`, which returns
// a value, and JUnit ignores a @Test method whose return type is not void.
//
// The cases below are chosen at the boundary rather than at round numbers, because the defect lived
// in exactly one interval and any figure outside it passes on the broken code.
@OptIn(ExperimentalUuidApi::class)
class UsageCounterClampTest {
    private val clock = KonektClock { Instant.fromEpochMilliseconds(1_700_000_000_000) }
    private val counters = ExposedUsageCounters(PostgresHarness.database, clock)
    private lateinit var subscriberId: String

    @BeforeTest
    fun seed() {
        PostgresHarness.truncateAll()
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

    private suspend fun remaining(): Long =
        assertNotNull(counters.find(subscriberId, UsageCounter.Kind.DATA)).remainingUnits

    private suspend fun limit(): Long = assertNotNull(counters.find(subscriberId, UsageCounter.Kind.DATA)).limitUnits

    @Test
    fun `consuming more than half of what is left keeps the rest`(): Unit =
        runBlocking {
            counters.grant(subscriberId, UsageCounter.Kind.DATA, 1_000)

            counters.consume(subscriberId, UsageCounter.Kind.DATA, 950)

            // 50, not 0. On the broken order the subtract left 50 and the clamp saw 50 < 950.
            assertEquals(50, remaining())
        }

    @Test
    fun `consuming exactly what is left leaves zero rather than wrapping`(): Unit =
        runBlocking {
            counters.grant(subscriberId, UsageCounter.Kind.DATA, 1_000)

            counters.consume(subscriberId, UsageCounter.Kind.DATA, 1_000)

            assertEquals(0, remaining())
        }

    @Test
    fun `consuming more than is left is floored at zero`(): Unit =
        runBlocking {
            counters.grant(subscriberId, UsageCounter.Kind.DATA, 10)

            counters.consume(subscriberId, UsageCounter.Kind.DATA, 400)

            assertEquals(0, remaining())
        }

    @Test
    fun `consuming less than half leaves the rest, which is the case that always worked`(): Unit =
        runBlocking {
            counters.grant(subscriberId, UsageCounter.Kind.DATA, 1_000)

            counters.consume(subscriberId, UsageCounter.Kind.DATA, 100)

            // The positive control. Without it "the clamp is correct" would be satisfied by an
            // implementation that never subtracts at all.
            assertEquals(900, remaining())
        }

    @Test
    fun `revoking more than half of an allowance keeps the rest of it`(): Unit =
        runBlocking {
            // Two purchases, then one of them rolled back. The interval is the same one: 1000 revoked
            // from a limit of 1800 left 800, and 800 < 1000 zeroed it — so a compensated purchase took
            // away the allowance the OTHER purchase had paid for.
            counters.grant(subscriberId, UsageCounter.Kind.DATA, 800)
            counters.grant(subscriberId, UsageCounter.Kind.DATA, 1_000)

            counters.revokePlanAllowance(subscriberId, dataMb = 1_000)

            assertEquals(800, limit())
            assertEquals(800, remaining())
        }

    @Test
    fun `revoking more than was ever granted is floored at zero`(): Unit =
        runBlocking {
            counters.grant(subscriberId, UsageCounter.Kind.DATA, 100)

            counters.revokePlanAllowance(subscriberId, dataMb = 500)

            assertEquals(0, limit())
            assertEquals(0, remaining())
        }
}
