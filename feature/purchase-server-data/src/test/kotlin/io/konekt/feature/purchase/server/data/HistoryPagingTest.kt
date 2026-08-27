package io.konekt.feature.purchase.server.data

import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.standard.ButtonComponent
import io.github.youndie.kompot.standard.PaginatedListComponent
import io.github.youndie.kompot.standard.TextComponent
import io.konekt.components.ButtonEmphasis
import io.konekt.components.OrderRowComponent
import io.konekt.components.OrderStatuses
import io.konekt.components.konektWalk
import io.konekt.db.tables.AccountTable
import io.konekt.db.tables.SubscriberTable
import io.konekt.domain.Currency
import io.konekt.domain.Money
import io.konekt.feature.purchase.server.domain.Entitlement
import io.konekt.feature.purchase.server.domain.HistoryCursor
import io.konekt.feature.purchase.server.domain.HistoryFilter
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

            val screen =
                HistoryScreen.build(
                    load(LoadHistoryUseCase.Params(subscriberId, null)).getOrThrow(),
                    PLAN_IDS_AS_TITLES,
                )
            val row = screen.theList().initialItems.single() as OrderRowComponent

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

            val screen =
                HistoryScreen.build(
                    load(LoadHistoryUseCase.Params(subscriberId, null)).getOrThrow(),
                    PLAN_IDS_AS_TITLES,
                )
            val row = screen.theList().initialItems.single() as OrderRowComponent

            // AWAITING_CONFIRMATION and not PENDING, and this expectation changed with B-41 rather
            // than the behaviour regressing. `HistoryScreen` used to map everything it did not name
            // to `pending` through an `else`, so this row's word said "in progress" while the
            // `statusText` beside it said "Awaiting confirmation" — two fields of one component
            // disagreeing. The mapping is exhaustive now and the word is the state.
            assertEquals(OrderStatuses.AWAITING_CONFIRMATION, row.status)
            assertEquals("Awaiting confirmation", row.statusText, "the word and the sentence disagree")
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
                seen += page.entries.map { it.reference }
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
            assertNull(HistoryScreen.build(page, PLAN_IDS_AS_TITLES).theList().loadMoreAction)
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

    // THE CASE THIS LIST COULD NOT SHOW, and it could not for a whole release: a top-up creates no
    // entitlement, the list was driven by entitlements, and the `Top up` button sits beside the
    // `History` button that would not show what it did.
    @Test
    fun `a top-up is a line, and it reads as money arriving`() =
        runBlocking {
            val topUpId = topUp(at = 1_719_532_800_000)

            val screen =
                HistoryScreen.build(
                    load(LoadHistoryUseCase.Params(subscriberId, null)).getOrThrow(),
                    PLAN_IDS_AS_TITLES,
                )
            val row = screen.theList().initialItems.single() as OrderRowComponent

            assertEquals(topUpId.take(8), row.reference)
            assertEquals("Top-up", row.title, "the row went looking for a plan a top-up does not have")
            // THE SIGN IS THE ASSERTION. The screen used to negate every amount, which was right
            // while every line was a debit; a credit drawn as "−$25" is the opposite of what
            // happened, on the one screen a subscriber reconciles against a bank statement.
            assertEquals("+$25", row.amountText)
            assertEquals(OrderStatuses.COMPLETED, row.status)
            assertEquals("Added", row.statusText, "a credit was labelled with a word about money leaving")
            assertNull(row.noteText)
        }

    @Test
    fun `a top-up taken back does not borrow the purchase's sentence`() =
        runBlocking {
            topUp(at = 1_719_532_800_000, reversedAt = 1_719_619_200_000)

            val page = load(LoadHistoryUseCase.Params(subscriberId, null)).getOrThrow()
            val screen = HistoryScreen.build(page, PLAN_IDS_AS_TITLES)
            val row = screen.theList().initialItems.single() as OrderRowComponent

            assertEquals(OrderStatuses.COMPENSATED, row.status)
            assertEquals("Taken back", row.statusText)
            // "returned to balance" would be the exact opposite: the money was taken OUT of it, and a
            // subscriber reading the purchase's sentence goes looking for an amount that is not there.
            assertEquals("$25 was taken back on 29 Jun — the payment did not settle.", row.noteText)
        }

    @Test
    fun `purchases and top-ups are one list in one order`() =
        runBlocking {
            // Interleaved on purpose, and the middle one is the point: a union of two queries pages
            // each source separately, so a boundary that falls between them either repeats the
            // purchase or drops the top-up.
            val first = topUp(at = 1_719_532_800_000)
            val second = order(at = 1_719_532_800_001, status = Entitlement.ACTIVE, reversedAt = null)
            val third = topUp(at = 1_719_532_800_002)

            val seen = mutableListOf<String>()
            var cursor: String? = null
            do {
                val page = load(LoadHistoryUseCase.Params(subscriberId, cursor, limit = 2)).getOrThrow()
                seen += page.entries.map { it.reference }
                cursor = page.next?.encode()
            } while (cursor != null)

            assertEquals(listOf(third, second, first), seen, "newest first, one list, each row once")
        }

    @Test
    fun `each slice shows its own rows and no others`() =
        runBlocking {
            val running = order(at = 1_719_532_800_000, status = Entitlement.ACTIVE, reversedAt = null)
            val reversed =
                order(at = 1_719_532_800_001, status = Entitlement.CANCELLED, reversedAt = 1_719_619_200_000)
            val credit = topUp(at = 1_719_532_800_002)
            val takenBack = topUp(at = 1_719_532_800_003, reversedAt = 1_719_619_200_001)

            suspend fun slice(filter: HistoryFilter) =
                load(LoadHistoryUseCase.Params(subscriberId, null, filter = filter))
                    .getOrThrow()
                    .entries
                    .map { it.reference }
                    .toSet()

            assertEquals(setOf(running, reversed, credit, takenBack), slice(HistoryFilter.ALL))

            // A top-up is NOT active: money that arrived is finished, not running. It has no
            // entitlement to be active, which is what makes this one clause rather than two.
            assertEquals(setOf(running), slice(HistoryFilter.ACTIVE))

            // BOTH DIRECTIONS. Somebody opening this slice is looking for money and does not care
            // which way it went — a purchase reversed and a top-up taken back are both refunds to
            // the person reading.
            assertEquals(setOf(reversed, takenBack), slice(HistoryFilter.REFUNDED))
        }

    // THE BUG THIS SHAPE PREVENTS, and it is the reason the filter is on the page URL rather than
    // only on the screen: a keyset cursor is a position in a FILTERED list. Asking for the next page
    // without the filter walks the unfiltered list from this boundary, and the client appends rows
    // the subscriber just narrowed away.
    @Test
    fun `paging inside a slice never leaves it`() =
        runBlocking {
            val refunded =
                (1..5)
                    .map {
                        order(
                            at = 1_719_532_800_000L + it,
                            status = Entitlement.CANCELLED,
                            reversedAt = 1_719_619_200_000L + it,
                        )
                    }.toSet()
            // Twice as many rows that must never appear, interleaved in time with the ones that must.
            repeat(10) { order(at = 1_719_532_800_000L + it, status = Entitlement.ACTIVE, reversedAt = null) }

            val seen = mutableListOf<String>()
            var cursor: String? = null
            var pages = 0
            do {
                val page =
                    load(
                        LoadHistoryUseCase.Params(subscriberId, cursor, limit = 2, filter = HistoryFilter.REFUNDED),
                    ).getOrThrow()
                seen += page.entries.map { it.reference }
                cursor = page.next?.encode()
                assertTrue(++pages <= 10, "the walk did not terminate — it is looping over $seen")
            } while (cursor != null)

            assertEquals(refunded, seen.toSet(), "paging inside a slice let another slice's rows in")
            assertEquals(refunded.size, seen.size, "a row came back twice: $seen")
        }

    @Test
    fun `an empty slice says which empty it is`() =
        runBlocking {
            order(at = 1_719_532_800_000, status = Entitlement.ACTIVE, reversedAt = null)

            val screen =
                HistoryScreen.build(
                    load(LoadHistoryUseCase.Params(subscriberId, null, filter = HistoryFilter.REFUNDED)).getOrThrow(),
                    PLAN_IDS_AS_TITLES,
                    HistoryFilter.REFUNDED,
                )

            // "Nothing here yet" would be wrong twice under `Refunded`: this subscriber has history,
            // and the sentence sends them looking for a fault instead of pressing `All`.
            assertEquals(
                "Nothing has been refunded.",
                (screen.theList().emptyState as? TextComponent)?.text,
            )
        }

    @Test
    fun `the chips say which slice is open`() =
        runBlocking {
            val screen =
                HistoryScreen.build(
                    load(LoadHistoryUseCase.Params(subscriberId, null, filter = HistoryFilter.ACTIVE)).getOrThrow(),
                    PLAN_IDS_AS_TITLES,
                    HistoryFilter.ACTIVE,
                )
            val chips =
                screen.konektWalk().filterIsInstance<ButtonComponent>().filter {
                    it.id.startsWith(
                        "history-filter-",
                    )
                }

            assertEquals(3, chips.size, "a slice gained a filter and lost its chip, or the other way round")
            // EXACTLY ONE is emphasised. Two would be a screen with two answers to one question, and
            // none would be a screen that does not say what it is showing.
            assertEquals(
                listOf("history-filter-active"),
                chips.filter { it.variant == ButtonEmphasis.PRIMARY }.map { it.id },
            )
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

    // THE HOLD IS NOT OPTIONAL HERE, and it used to be absent.
    //
    // This fixture wrote an entitlement and no ledger row, which was a half-real order: the product
    // writes both in one interceptor (`HoldFundsInterceptor`), because a purchase that reserved
    // nothing is not a purchase. The list is driven by the ledger now, so the omission stopped being
    // harmless — and a fixture that cannot be built the way the product builds it was never testing
    // the product.
    // THE ROOT IS A COLUMN NOW, and it used to be the list itself. The filter chips sit above it, so
    // a screen with no chrome is no longer the same node as its list — and `konektWalk` is how a test
    // stops caring which, rather than a cast that breaks the day the tree gains a level.
    private fun KompotComponent.theList(): PaginatedListComponent =
        konektWalk().filterIsInstance<PaginatedListComponent>().single()

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
            ledger(theAccountId, orderId, LedgerEntryTable.HOLD, -price.minorUnits, at)
            reversedAt?.let { moment ->
                ledger(theAccountId, orderId, LedgerEntryTable.RELEASE, price.minorUnits, moment)
            }
        }
        return orderId
    }

    // MONEY IN, and it has no entitlement — which is the whole reason no top-up was ever a row.
    private fun topUp(
        at: Long,
        amount: Money = Money.ofMajor(25, Currency.DEFAULT),
        reversedAt: Long? = null,
    ): String {
        val topUpId = Uuid.random().toString()
        val theAccountId = accountId
        transaction(PostgresHarness.database) {
            ledger(theAccountId, topUpId, LedgerEntryTable.TOP_UP, amount.minorUnits, at)
            reversedAt?.let { moment ->
                ledger(theAccountId, topUpId, LedgerEntryTable.TOP_UP_REVERSAL, -amount.minorUnits, moment)
            }
        }
        return topUpId
    }

    private fun ledger(
        theAccountId: String,
        reference: String,
        kind: String,
        amountMinor: Long,
        at: Long,
    ) {
        LedgerEntryTable.insert {
            it[id] = Uuid.random().toString()
            it[accountId] = theAccountId
            it[orderId] = reference
            it[LedgerEntryTable.kind] = kind
            it[LedgerEntryTable.amountMinor] = amountMinor
            it[currency] = price.currency.name
            it[createdAt] = at
        }
    }
}

// The identity lookup. These tests are about PAGING — cursors, boundaries, the row that comes back —
// and a catalogue would add a second thing that can be wrong to every assertion about the first.
private val PLAN_IDS_AS_TITLES = HistoryScreen.PlanTitles { it }
