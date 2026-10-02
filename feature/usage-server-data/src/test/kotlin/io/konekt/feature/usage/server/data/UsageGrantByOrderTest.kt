package io.konekt.feature.usage.server.data

import io.konekt.db.tables.SubscriberTable
import io.konekt.domain.suspendRunCatching
import io.konekt.feature.usage.server.domain.UsageCounter
import io.konekt.testing.PostgresHarness
import io.konekt.time.KonektClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

// THE HOME ALLOWANCE HAS A NAME NOW, AND THE NAME IS THE ORDER (B-130).
//
// The counters are running totals: nothing in them says which purchase added what. So a grant could
// not tell a second run of the purchase member from a second purchase — it added twice — and a
// rollback could not take back a grant whose answer it never heard. `usage_grant` keys each grant by
// `(order_id, kind)` in the transaction that moves the counter, and the revoke marks that row before
// it subtracts. These pin the SQL; `ProvisionByOrderTest` drives the same thing through the saga.
//
// Against a real Postgres because both guarantees live there — `ON CONFLICT DO NOTHING` and a
// predicate re-evaluated after a concurrent commit — and H2's compatibility mode is not either of them.
@OptIn(ExperimentalUuidApi::class)
class UsageGrantByOrderTest {
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
                it[msisdn] = "1555011${(1000..9999).random()}"
                it[createdAt] = 0
            }
        }
    }

    private suspend fun allowance(): List<Long> =
        listOf(UsageCounter.Kind.DATA, UsageCounter.Kind.MINUTES, UsageCounter.Kind.MESSAGES).map { kind ->
            counters.find(subscriberId, kind)?.limitUnits ?: 0
        }

    private suspend fun grant(orderId: String) = counters.grantPlanAllowance(orderId, subscriberId, 1_000, 300, 50)

    // THE POSITIVE CONTROL. Every refusal below is also satisfied by a grant that adds nothing at all.
    @Test
    fun `two orders are two grants`(): Unit =
        runBlocking {
            grant("order-1")
            grant("order-2")

            assertEquals(listOf(2_000L, 600L, 100L), allowance())
        }

    @Test
    fun `the same order granted twice adds once`(): Unit =
        runBlocking {
            grant("order-1")
            grant("order-1")

            assertEquals(listOf(1_000L, 300L, 50L), allowance(), "a second run of one purchase added its plan again")
        }

    // AND CONCURRENTLY, because two sequential calls would also pass on a read-then-write guard.
    @Test
    fun `two grants of one order racing add once`(): Unit =
        runBlocking {
            @Suppress(
                "ktlint:kapkan:cancellation-swallowed",
                "a test racing two grants: capturing the throw is what it measures",
            )
            withContext(Dispatchers.IO) {
                listOf(
                    async { suspendRunCatching { grant("order-1") } },
                    async { suspendRunCatching { grant("order-1") } },
                ).awaitAll()
            }

            assertEquals(listOf(1_000L, 300L, 50L), allowance(), "two racing runs of one purchase both added")
        }

    // The case the undo used to have no answer for in the other direction: a grant that never landed
    // has nothing under its order, so taking back by that name must leave an earlier plan alone.
    @Test
    fun `revoking an order with nothing under it takes nothing`(): Unit =
        runBlocking {
            grant("order-1")

            counters.revokePlanAllowance("order-2")

            assertEquals(listOf(1_000L, 300L, 50L), allowance(), "a revoke of nothing took an earlier plan")
        }

    @Test
    fun `revoking an order twice takes it back once`(): Unit =
        runBlocking {
            grant("order-1")
            grant("order-2")

            counters.revokePlanAllowance("order-2")
            counters.revokePlanAllowance("order-2")

            assertEquals(
                listOf(1_000L, 300L, 50L),
                allowance(),
                "a second compensation took the other order's allowance",
            )
        }

    @Test
    fun `two revokes of one order racing take it back once`(): Unit =
        runBlocking {
            grant("order-1")
            grant("order-2")

            @Suppress(
                "ktlint:kapkan:cancellation-swallowed",
                "a test racing two compensations: capturing the throw is what it measures",
            )
            withContext(Dispatchers.IO) {
                listOf(
                    async { suspendRunCatching { counters.revokePlanAllowance("order-2") } },
                    async { suspendRunCatching { counters.revokePlanAllowance("order-2") } },
                ).awaitAll()
            }

            assertEquals(listOf(1_000L, 300L, 50L), allowance(), "two racing compensations both took it back")
        }

    // A revoked order stays spent: a late re-run of the member that granted it must not bring the
    // allowance back after the purchase was rolled back.
    @Test
    fun `an order taken back is not granted again`(): Unit =
        runBlocking {
            grant("order-1")
            counters.revokePlanAllowance("order-1")

            grant("order-1")

            assertEquals(listOf(0L, 0L, 0L), allowance(), "a rolled-back order was granted again")
        }
}
