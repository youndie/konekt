package io.konekt.feature.purchase.server.data

import io.konekt.db.tables.AccountTable
import io.konekt.db.tables.SubscriberTable
import io.konekt.domain.Currency
import io.konekt.domain.Money
import io.konekt.testing.PostgresHarness
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

// THE MONEY, ASSERTED AGAINST A REAL POSTGRES — which is the only place the fix exists.
//
// `B-64`: nothing claims an expired saga before compensating it, so every process running a sweeper
// returned the same hold. Two contours on one database here, two replicas in a deployment. The stand
// had one `hold` of -900 and two `release`s of +900 two milliseconds apart, and the account and the
// ledger agreed with each other about a subscriber $9 richer than they paid in.
//
// The fix is a unique index on `(order_id, kind)` and the entry written BEFORE the balance moves, so a
// second attempt violates it and the transaction rolls back untouched. Neither half can be tested
// without a database: the index is the database, and H2's compatibility mode is not it.
@OptIn(ExperimentalUuidApi::class)
class OneRefundPerHoldTest {
    private val price = Money.ofMajor(9, Currency.DEFAULT)
    private lateinit var subscriberId: String
    private lateinit var accountId: String

    private val balances = ExposedAccountBalances(PostgresHarness.database, io.konekt.time.SystemClock)

    @BeforeTest
    fun seed() {
        PostgresHarness.truncateAll()
        val sub = Uuid.random().toString()
        val acc = Uuid.random().toString()
        subscriberId = sub
        accountId = acc
        transaction(PostgresHarness.database) {
            SubscriberTable.insert {
                it[id] = sub
                it[msisdn] = "15550107777"
                it[createdAt] = 0
            }
            AccountTable.insert {
                it[id] = acc
                it[AccountTable.subscriberId] = sub
                it[balanceMinor] = 5_000
                it[currency] = Currency.DEFAULT.name
                it[createdAt] = 0
            }
        }
    }

    private fun balanceNow(): Long =
        transaction(PostgresHarness.database) {
            AccountTable.selectAll().where { AccountTable.id eq accountId }.single()[AccountTable.balanceMinor]
        }

    private fun releasesFor(orderId: String): Int =
        transaction(PostgresHarness.database) {
            LedgerEntryTable
                .selectAll()
                .where { (LedgerEntryTable.orderId eq orderId) and (LedgerEntryTable.kind eq LedgerEntryTable.RELEASE) }
                .count()
                .toInt()
        }

    @Test
    fun `compensating the same order twice returns the hold once`() =
        runBlocking {
            val orderId = Uuid.random().toString()
            balances.hold(accountId, orderId, price)
            assertEquals(4_100, balanceNow(), "the hold did not come off the balance")

            // TWICE, IN SEQUENCE — which is what two sweepers a moment apart actually do, and what the
            // stand produced. The second call must be a no-op rather than an error: a compensation
            // that runs again has nothing to do, and that is not a failure to report.
            balances.release(accountId, orderId, price)
            balances.release(accountId, orderId, price)

            assertEquals(1, releasesFor(orderId), "the ledger recorded the refund twice")
            assertEquals(5_000, balanceNow(), "the subscriber was refunded more than was held")
        }

    // AND CONCURRENTLY, because sequential calls would also pass on a read-then-write guard — the
    // shape this repository refuses by name elsewhere. Two transactions that both look first and both
    // find nothing is exactly how a double refund survives a check that reads.
    @Test
    fun `two compensations racing return the hold once`() =
        runBlocking {
            val orderId = Uuid.random().toString()
            balances.hold(accountId, orderId, price)

            withContext(Dispatchers.IO) {
                listOf(
                    async { runCatching { balances.release(accountId, orderId, price) } },
                    async { runCatching { balances.release(accountId, orderId, price) } },
                ).awaitAll()
            }

            assertEquals(1, releasesFor(orderId), "two racing compensations both wrote a refund")
            assertEquals(5_000, balanceNow(), "the balance moved twice for one hold")
        }

    // THE POSITIVE CONTROL. Without it both assertions above are satisfied by a `release` that does
    // nothing at all — which would be a worse defect than the one being fixed: money held and never
    // returned is a subscriber out of pocket rather than an operator.
    @Test
    fun `a first compensation still returns the money`() =
        runBlocking {
            val orderId = Uuid.random().toString()
            balances.hold(accountId, orderId, price)
            balances.release(accountId, orderId, price)

            assertEquals(1, releasesFor(orderId))
            assertEquals(5_000, balanceNow(), "the refund did not happen at all")
        }

    // TWO DIFFERENT ORDERS ARE TWO REFUNDS, which is what makes the index per ORDER rather than per
    // kind. A guard that refused the second refund outright would strand every order after the first.
    @Test
    fun `two different orders each get their own refund`() =
        runBlocking {
            val first = Uuid.random().toString()
            val second = Uuid.random().toString()
            balances.hold(accountId, first, price)
            balances.hold(accountId, second, price)
            balances.release(accountId, first, price)
            balances.release(accountId, second, price)

            assertEquals(1, releasesFor(first))
            assertEquals(1, releasesFor(second))
            assertEquals(5_000, balanceNow())
        }
}
