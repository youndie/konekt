package io.konekt.feature.purchase.server.data

import io.github.youndie.petich.EnrichedPayload
import io.github.youndie.petich.PetichEngine
import io.github.youndie.petich.PetichEngineConfig
import io.github.youndie.petich.PetichPayload
import io.github.youndie.petich.PetichStepRecord
import io.github.youndie.petich.ResumePayload
import io.github.youndie.petich.SimpleEnrichedPayload
import io.github.youndie.petich.postgres.ExposedPetichRepository
import io.github.youndie.petich.postgres.OutboxEventsTable
import io.github.youndie.petich.postgres.PetichTable
import io.konekt.db.tables.AccountTable
import io.konekt.db.tables.SubscriberTable
import io.konekt.domain.Currency
import io.konekt.domain.Money
import io.konekt.feature.purchase.server.domain.AccountBalances
import io.konekt.feature.purchase.server.domain.Entitlements
import io.konekt.feature.purchase.server.domain.Held
import io.konekt.feature.purchase.server.domain.HistoryFilter
import io.konekt.feature.purchase.server.domain.OrderStatus
import io.konekt.feature.purchase.server.domain.Provisioned
import io.konekt.feature.purchase.server.domain.PurchaseConfirmation
import io.konekt.feature.purchase.server.domain.PurchasePayload
import io.konekt.feature.purchase.server.domain.StartPurchaseUseCase
import io.konekt.feature.purchase.server.domain.purchasePetich
import io.konekt.feature.roaming.server.domain.InMemoryRoamingPackages
import io.konekt.feature.usage.server.data.ExposedUsageCounters
import io.konekt.testing.PostgresHarness
import io.konekt.time.KonektClock
import io.konekt.time.asPetichClock
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

// THE HOLD COMES BACK WHEN THE MEMBER THAT TOOK IT DIES BEFORE SAYING SO.
//
// `HoldFunds.execute` makes two writes in two transactions — the hold, then the pending entitlement —
// and petich compensates a member whose `execute` threw, because it cannot tell an effect that landed
// from a call that never did. konekt#49 had that undo ask the member's own step record first and
// return quietly without one; the record is written after both calls, so a hold whose entitlement
// failed, or whose own answer was lost after the commit, had no record and stayed taken for good.
// That is the blind spot youndie/petich B-43 describes, and the rule it ends with: undo by the name the
// member chose before the call — here the order id the ledger is keyed by — never by the evidence.
//
// Through the real engine and a real Postgres, because both halves of the claim live there: the
// engine decides which member is compensated, and the ledger decides whether there was anything to
// return. The failing ports are wrappers around the real ones, so every write that did land is a real
// row.
//
// `runBlocking` and not `runTest`, for the reason PurchaseSagaTest carries.
@OptIn(ExperimentalUuidApi::class)
class HoldRollbackTest {
    private val clock = KonektClock { Instant.fromEpochMilliseconds(1_700_000_000_000) }

    private val json =
        Json {
            serializersModule =
                SerializersModule {
                    polymorphic(PetichPayload::class) { subclass(PurchasePayload::class) }
                    polymorphic(EnrichedPayload::class) { subclass(SimpleEnrichedPayload::class) }
                    polymorphic(ResumePayload::class) { subclass(PurchaseConfirmation::class) }
                    polymorphic(PetichStepRecord::class) {
                        subclass(Held::class)
                        subclass(Provisioned::class)
                    }
                }
        }

    private val repository = ExposedPetichRepository(PostgresHarness.database, PetichTable(json), OutboxEventsTable())
    private val balances = ExposedAccountBalances(PostgresHarness.database, clock)
    private val entitlements = ExposedEntitlements(PostgresHarness.database, clock)
    private val history = ExposedHistoryRepository(PostgresHarness.database)
    private val plans = StaticPlanCatalog()

    private val opening = Money.ofMajor(50, Currency.DEFAULT)
    private val planId = "tr-10gb-30d"
    private lateinit var subscriberId: String

    private fun startWith(
        balances: AccountBalances = this.balances,
        entitlements: Entitlements = this.entitlements,
    ): StartPurchaseUseCase {
        val engine =
            PetichEngine(
                definitions =
                    listOf(
                        purchasePetich(
                            balances,
                            entitlements,
                            plans,
                            MockPaymentGateway(),
                            ExposedUsageCounters(PostgresHarness.database, clock),
                            InMemoryRoamingPackages { clock.now() },
                            clock,
                            json,
                            5.minutes,
                        ),
                    ),
                repository = repository,
                config = PetichEngineConfig(requireOutbox = true),
                clock = clock.asPetichClock(),
            )
        // The use case reads the account and the decline reason through the REAL port: what fails is
        // the saga's member, not the request around it.
        return StartPurchaseUseCase(engine, repository, plans, this.balances)
    }

    @BeforeTest
    fun seed() {
        PostgresHarness.truncateAll()
        val newSubscriberId = Uuid.random().toString()
        subscriberId = newSubscriberId
        transaction(PostgresHarness.database) {
            SubscriberTable.insert {
                it[id] = newSubscriberId
                it[msisdn] = "15550108888"
                it[createdAt] = 0
            }
            AccountTable.insert {
                it[id] = Uuid.random().toString()
                it[AccountTable.subscriberId] = newSubscriberId
                it[balanceMinor] = opening.minorUnits
                it[currency] = opening.currency.name
                it[createdAt] = 0
            }
        }
    }

    // The case the item was opened for: the hold committed, the entitlement write threw, and the
    // member never reached anything it would have written after it.
    // `: Unit` because the body ends in `assertNotNull`, which returns its argument — and JUnit skips a
    // test method that returns anything, silently. Watched happening on the first run of this file.
    @Test
    fun `a hold whose entitlement could not be written is returned`(): Unit =
        runBlocking {
            val started = startWith(entitlements = EntitlementsThatCannotWrite(entitlements))(params()).getOrThrow()

            assertEquals(OrderStatus.COMPENSATED, started.status)
            assertEquals(
                1,
                ledgerEntries(started.orderId, LedgerEntryTable.HOLD),
                "the hold never happened — nothing was tested",
            )
            assertEquals(opening, balance(), "the hold was kept after its member failed")
            assertEquals(1, ledgerEntries(started.orderId, LedgerEntryTable.RELEASE))
            assertNull(entitlements.findByOrder(started.orderId), "the failing write was not the entitlement's")
            assertEquals(
                listOf("${started.orderId}:purchase.reversed"),
                outboxIds(),
                "money moved and came back, and a consumer was not told",
            )

            // AND THE HISTORY SAYS SO. A purchase row is driven by the hold and asks the entitlement
            // for its state; there is none here, so the row reads the reversal instead of claiming the
            // order still waits for a confirmation nobody can give.
            val row = history.page(subscriberId, after = null, limit = 10, filter = HistoryFilter.ALL).entries.single()
            assertEquals(OrderStatus.COMPENSATED, row.status, "the row says the order still waits for a confirmation")
            assertNotNull(row.reversal, "the row does not say the money came back")
        }

    // The case B-43 is written about: the hold committed and only its answer was lost — a reset
    // connection, a deadline that fired after the commit. No write ordering inside the member could
    // record it, because the member never learned it.
    @Test
    fun `a hold whose answer was lost is returned`() =
        runBlocking {
            val started = startWith(balances = HoldWhoseAnswerIsLost(balances))(params()).getOrThrow()

            assertEquals(OrderStatus.COMPENSATED, started.status)
            assertEquals(
                1,
                ledgerEntries(started.orderId, LedgerEntryTable.HOLD),
                "the hold never happened — nothing was tested",
            )
            assertEquals(opening, balance(), "a hold that committed was kept because its answer was lost")
            assertEquals(1, ledgerEntries(started.orderId, LedgerEntryTable.RELEASE))
        }

    // THE CONTROL, and the reason the undo cannot simply return the price. Undoing by name has to be
    // a no-op when nothing is under the name: a release here would be money that was never taken,
    // and a reversal announced here would be an event about a purchase that never moved anything.
    @Test
    fun `a hold that never landed is neither refunded nor announced`() =
        runBlocking {
            val started = startWith(balances = HoldThatNeverLands(balances))(params()).getOrThrow()

            assertEquals(OrderStatus.COMPENSATED, started.status)
            assertEquals(0, ledgerEntries(started.orderId, LedgerEntryTable.HOLD))
            assertEquals(opening, balance(), "money was returned that was never taken")
            assertEquals(0, ledgerEntries(started.orderId, LedgerEntryTable.RELEASE))
            assertEquals(emptyList(), outboxIds(), "a reversal was announced for a hold that never happened")
        }

    private fun params() = StartPurchaseUseCase.Params(subscriberId, planId)

    private fun balance(): Money =
        transaction(PostgresHarness.database) {
            AccountTable
                .selectAll()
                .where { AccountTable.subscriberId eq subscriberId }
                .single()
                .let { Money(it[AccountTable.balanceMinor], Currency.valueOf(it[AccountTable.currency].trim())) }
        }

    private fun ledgerEntries(
        orderId: String,
        kind: String,
    ): Int =
        transaction(PostgresHarness.database) {
            LedgerEntryTable
                .selectAll()
                .where { (LedgerEntryTable.orderId eq orderId) and (LedgerEntryTable.kind eq kind) }
                .count()
                .toInt()
        }

    private fun outboxIds(): List<String> =
        transaction(PostgresHarness.database) {
            OutboxEventsTable().let { table -> table.selectAll().map { it[table.id] } }
        }

    // The entitlement write fails after the hold has committed.
    private class EntitlementsThatCannotWrite(
        real: Entitlements,
    ) : Entitlements by real {
        override suspend fun createPending(
            orderId: String,
            subscriberId: String,
            planId: String,
            price: Money,
        ): Unit = error("the entitlement write did not go through")
    }

    // The hold commits, and the caller hears a failure instead of the answer.
    private class HoldWhoseAnswerIsLost(
        private val real: AccountBalances,
    ) : AccountBalances by real {
        override suspend fun hold(
            accountId: String,
            orderId: String,
            amount: Money,
        ): Boolean {
            real.hold(accountId, orderId, amount)
            error("the connection dropped after the hold committed")
        }
    }

    // The hold never reaches the database at all.
    private class HoldThatNeverLands(
        real: AccountBalances,
    ) : AccountBalances by real {
        override suspend fun hold(
            accountId: String,
            orderId: String,
            amount: Money,
        ): Boolean = error("the database did not answer")
    }
}
