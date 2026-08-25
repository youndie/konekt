package io.konekt.feature.purchase.server.data

import io.github.youndie.kompot.standard.PaginatedListComponent
import io.konekt.components.OrderRowComponent
import io.konekt.components.OrderStatuses
import io.konekt.db.tables.AccountTable
import io.konekt.db.tables.SubscriberTable
import io.konekt.domain.Currency
import io.konekt.domain.Money
import io.konekt.feature.purchase.server.domain.Entitlement
import io.konekt.feature.purchase.server.domain.HistoryCursor
import io.konekt.feature.purchase.server.domain.LoadHistoryUseCase
import io.konekt.testing.PostgresHarness
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

// The list, its rows, and the property a paginated list has to have: that walking it visits every
// row exactly once and stops.
@OptIn(ExperimentalUuidApi::class)
class HistoryPagingTest {
    private val price = Money.ofMajor(12, Currency.DEFAULT)
    private lateinit var subscriberId: String
    private lateinit var accountId: String

    private val history = ExposedHistoryRepository(PostgresHarness.database)
    private val load = LoadHistoryUseCase(history)

    @BeforeTest
    fun seed() {
        PostgresHarness.truncateAll()
        val newSubscriberId = Uuid.random().toString()
        val newAccountId = Uuid.random().toString()
        subscriberId = newSubscriberId
        accountId = newAccountId
        transaction(PostgresHarness.database) {
            SubscriberTable.insert {
                it[id] = newSubscriberId
                it[msisdn] = "15550109999"
                it[createdAt] = 0
            }
            AccountTable.insert {
                it[id] = newAccountId
                it[AccountTable.subscriberId] = newSubscriberId
                it[balanceMinor] = 0
                it[currency] = Currency.DEFAULT.name
                it[createdAt] = 0
            }
        }
    }

    @Test
    fun `a compensated order carries its reversal line and its reference`() =
        runBlocking {
            val orderId = order(at = 1_719_532_800_000, status = Entitlement.CANCELLED, reversedAt = 1_719_619_200_000)

            val screen = HistoryScreen.build(load(LoadHistoryUseCase.Params(subscriberId, null)).getOrThrow())
            val row = (screen as PaginatedListComponent).initialItems.single() as OrderRowComponent

            assertEquals(orderId.take(8), row.reference)
            assertEquals(OrderStatuses.COMPENSATED, row.status)
            // The debit stays on the row. Netting it against the reversal would make the reversal
            // invisible, which is the one thing this screen must not do.
            assertEquals("−$12", row.amountText)
            assertEquals("$12 returned to balance on 29 Jun — nothing was activated.", row.noteText)
        }

    @Test
    fun `an order still in flight is not dressed up as finished`() =
        runBlocking {
            order(at = 1_719_532_800_000, status = Entitlement.PENDING, reversedAt = null)

            val screen = HistoryScreen.build(load(LoadHistoryUseCase.Params(subscriberId, null)).getOrThrow())
            val row = (screen as PaginatedListComponent).initialItems.single() as OrderRowComponent

            assertEquals(OrderStatuses.PENDING, row.status)
            assertNull(row.noteText, "an unfinished order was given a reversal line")
        }

    @Test
    fun `walking every page visits each order exactly once and stops`() =
        runBlocking {
            // Seven rows sharing a single instant, which is the case a cursor on the timestamp alone
            // gets wrong: `< instant` drops all seven at a boundary, `<= instant` returns them
            // forever. Two purchases in one millisecond are not exotic — a retry does it.
            val expected =
                (1..7)
                    .map {
                        order(
                            at = 1_719_532_800_000,
                            status = Entitlement.ACTIVE,
                            reversedAt = null,
                        )
                    }.toSet()

            val seen = mutableListOf<String>()
            var cursor: String? = null
            var pages = 0

            do {
                val page = load(LoadHistoryUseCase.Params(subscriberId, cursor, limit = 3)).getOrThrow()
                seen += page.entries.map { it.orderId }
                cursor = page.next?.encode()
                pages++
                assertTrue(pages <= 10, "the walk did not terminate — it is looping over $seen")
            } while (cursor != null)

            assertEquals(expected, seen.toSet(), "the walk missed or invented an order")
            assertEquals(expected.size, seen.size, "the walk returned an order twice: $seen")
            assertEquals(3, pages, "seven rows at three per page should be three requests, not $pages")
        }

    @Test
    fun `the last page says there is no more`() =
        runBlocking {
            repeat(3) { order(at = 1_719_532_800_000L + it, status = Entitlement.ACTIVE, reversedAt = null) }

            val page = load(LoadHistoryUseCase.Params(subscriberId, null, limit = 10)).getOrThrow()

            // The client stops asking on a null, so this is the assertion that "terminates" rests on.
            // It comes from having fetched one row more than asked for, not from a count.
            assertNull(page.next)
            assertNull(HistoryScreen.build(page).let { (it as PaginatedListComponent).loadMoreAction })
        }

    @Test
    fun `an exactly full page still knows whether there is more`(): Unit =
        runBlocking {
            // The off-by-one every keyset gets wrong once: three rows, three per page. There is no
            // fourth, so the first page is the last — and a naive "a full page means there is more"
            // hands the client a second request that returns nothing.
            repeat(3) { order(at = 1_719_532_800_000L + it, status = Entitlement.ACTIVE, reversedAt = null) }

            assertNull(load(LoadHistoryUseCase.Params(subscriberId, null, limit = 3)).getOrThrow().next)

            order(at = 1_719_532_900_000, status = Entitlement.ACTIVE, reversedAt = null)
            assertNotNull(load(LoadHistoryUseCase.Params(subscriberId, null, limit = 3)).getOrThrow().next)
        }

    @Test
    fun `another subscriber's history is not in this one`() =
        runBlocking {
            order(at = 1_719_532_800_000, status = Entitlement.ACTIVE, reversedAt = null)

            val theirs = load(LoadHistoryUseCase.Params("somebody-else", null)).getOrThrow()

            // The filter is a WHERE clause, and a WHERE clause is the thing that is right until
            // somebody widens it.
            assertTrue(theirs.entries.isEmpty())
        }

    @Test
    fun `a cursor survives being written down and read back`() {
        val cursor = HistoryCursor(kotlin.time.Instant.fromEpochMilliseconds(1_719_532_800_000), "order-1")

        assertEquals(cursor, HistoryCursor.decode(cursor.encode()))
        // Anything a client made up is refused rather than half-read, because a half-read cursor is a
        // page boundary nobody chose.
        assertNull(HistoryCursor.decode("nonsense"))
        assertNull(HistoryCursor.decode(null))
    }

    private fun order(
        at: Long,
        status: String,
        reversedAt: Long?,
    ): String {
        val orderId = Uuid.random().toString()
        val theAccountId = accountId
        val theSubscriberId = subscriberId
        transaction(PostgresHarness.database) {
            EntitlementTable.insert {
                it[id] = Uuid.random().toString()
                it[EntitlementTable.orderId] = orderId
                it[EntitlementTable.subscriberId] = theSubscriberId
                it[planId] = "tr-10gb-30d"
                it[EntitlementTable.status] = status
                it[priceMinor] = price.minorUnits
                it[currency] = price.currency.name
                it[createdAt] = at
            }
            reversedAt?.let { moment ->
                LedgerEntryTable.insert {
                    it[id] = Uuid.random().toString()
                    it[accountId] = theAccountId
                    it[LedgerEntryTable.orderId] = orderId
                    it[kind] = LedgerEntryTable.RELEASE
                    it[amountMinor] = price.minorUnits
                    it[currency] = price.currency.name
                    it[createdAt] = moment
                }
            }
        }
        return orderId
    }
}
