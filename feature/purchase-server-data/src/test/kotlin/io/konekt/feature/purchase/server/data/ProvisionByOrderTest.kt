package io.konekt.feature.purchase.server.data

import io.github.youndie.petich.EnrichedPayload
import io.github.youndie.petich.OutboxAwarePetichRepository
import io.github.youndie.petich.OutboxEvent
import io.github.youndie.petich.Petich
import io.github.youndie.petich.PetichEngine
import io.github.youndie.petich.PetichEngineConfig
import io.github.youndie.petich.PetichPayload
import io.github.youndie.petich.PetichStatus
import io.github.youndie.petich.PetichStepRecord
import io.github.youndie.petich.ResumePayload
import io.github.youndie.petich.SimpleEnrichedPayload
import io.github.youndie.petich.SuspendedPetichSweeper
import io.github.youndie.petich.postgres.ExposedPetichRepository
import io.github.youndie.petich.postgres.OutboxEventsTable
import io.github.youndie.petich.postgres.PetichTable
import io.konekt.db.tables.AccountTable
import io.konekt.db.tables.SubscriberTable
import io.konekt.domain.Currency
import io.konekt.domain.Money
import io.konekt.feature.purchase.server.domain.ConfirmPurchaseUseCase
import io.konekt.feature.purchase.server.domain.Entitlement
import io.konekt.feature.purchase.server.domain.Held
import io.konekt.feature.purchase.server.domain.OrderStatus
import io.konekt.feature.purchase.server.domain.PaymentGateway
import io.konekt.feature.purchase.server.domain.Provisioned
import io.konekt.feature.purchase.server.domain.PurchaseConfirmation
import io.konekt.feature.purchase.server.domain.PurchasePayload
import io.konekt.feature.purchase.server.domain.StartPurchaseUseCase
import io.konekt.feature.purchase.server.domain.purchasePetich
import io.konekt.feature.roaming.server.domain.InMemoryRoamingPackages
import io.konekt.feature.usage.server.data.ExposedUsageCounters
import io.konekt.feature.usage.server.domain.UsageCounter
import io.konekt.feature.usage.server.domain.UsageGrants
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
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

// `Provision` RUNS AGAIN, AND IS UNDONE BY THE ORDER'S NAME (B-130).
//
// Two claims about the member that settles, captures and grants, both read out of the code before
// any of this existed and both checked here against the real engine and a real Postgres:
//
// - **It can run twice.** petich writes a member's position after its body returns, so a conflict on
//   that write re-reads the row and runs the same member again — on a healthy instance, under nothing
//   worse than a second writer touching the saga. Every effect of `Provision` therefore has to land
//   once however many times it is asked: the `CAPTURE` entry, which a plain insert under
//   `(order_id, kind)` refused the second time, and the home allowance, which added again.
// - **Its undo cannot go by its own record.** A grant that committed and lost its answer leaves no
//   record, so the allowance stayed. The allowance is now written under the order, and the undo
//   takes back what is under that name — nothing, when nothing landed.
//
// The failing ports are wrappers around the real ones, so every write that did land is a real row,
// and `runBlocking` rather than `runTest` for the reason PurchaseSagaTest carries.
@OptIn(ExperimentalUuidApi::class)
class ProvisionByOrderTest {
    private class MovableClock(
        private var now: Instant = Instant.fromEpochMilliseconds(1_700_000_000_000),
    ) : KonektClock {
        override fun now(): Instant = now

        fun advance(by: Duration) {
            now += by
        }
    }

    private val clock = MovableClock()

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

    // On the test's clock, because the stranded queue asks the store when a row was last written —
    // and a store stamping the wall clock would never look stale to a sweeper reading this one.
    private val repository =
        ExposedPetichRepository(PostgresHarness.database, PetichTable(json), OutboxEventsTable(), clock.asPetichClock())
    private val balances = ExposedAccountBalances(PostgresHarness.database, clock)
    private val entitlements = ExposedEntitlements(PostgresHarness.database, clock)
    private val counters = ExposedUsageCounters(PostgresHarness.database, clock)
    private val plans = StaticPlanCatalog()

    private val opening = Money.ofMajor(50, Currency.DEFAULT)

    // THE HOME BUNDLE, because it is the branch that adds to a counter. Its three numbers are what
    // every assertion below compares against: a second grant reads as twice them, and a lost one
    // that was not taken back reads as them on top of an earlier purchase.
    private val homePlan = "home-20gb-30d"
    private val price = Money.ofMajor(15, Currency.DEFAULT)
    private val dataMb = 20 * 1_024L
    private val minutes = 300L
    private val messages = 50L

    private lateinit var subscriberId: String

    private fun engine(
        repository: OutboxAwarePetichRepository = this.repository,
        grants: UsageGrants = counters,
        payments: PaymentGateway = MockPaymentGateway(),
    ): PetichEngine =
        PetichEngine(
            definitions =
                listOf(
                    purchasePetich(
                        balances,
                        entitlements,
                        plans,
                        payments,
                        grants,
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

    @BeforeTest
    fun seed() {
        PostgresHarness.truncateAll()
        val newSubscriberId = Uuid.random().toString()
        subscriberId = newSubscriberId
        transaction(PostgresHarness.database) {
            SubscriberTable.insert {
                it[id] = newSubscriberId
                it[msisdn] = "15550106666"
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

    // THE RE-RUN, driven the way the engine documents it: the write that would move the saga past
    // `provision` finds the row touched by somebody else — here the same one-write claim the stranded
    // queue makes, a version bump that moves nothing — so petich re-reads it and runs `provision`
    // again. Before this change the second `capture` hit the unique index, the member threw, and the
    // purchase the first run had completed was rolled back: money returned, entitlement cancelled,
    // and the allowance the first run granted left with the subscriber.
    @Test
    fun `a provision run again after a version conflict completes the purchase once`() =
        runBlocking {
            val touched = FirstWriteAfterTheGrant(repository) { written -> touch(written.id) }
            val payments = CountingPayments(MockPaymentGateway())
            val engine = engine(repository = touched, grants = GrantsThatReport(counters, touched), payments = payments)

            val orderId = startHome(engine)
            val confirmed = confirm(engine, orderId).getOrThrow()

            // The re-run happened, which both halves below are about: settling is the first thing
            // `provision` does, so two settlements are two runs of it, however the second one ended.
            assertEquals(1, touched.fired, "nothing touched the saga — the re-run was never provoked")
            assertEquals(2, payments.settled, "provision ran once — nothing here was run again")
            assertBoughtOnce(orderId, confirmed.status)
        }

    // THE SAME RE-RUN AFTER A RESTART. The process stops between `provision`'s effects and the write
    // that would record them, so the row is left moving with nothing moving it — and since petich
    // B-66 a confirmation writes its start before it runs a member, so that row says PROCESSING and is
    // the stranded queue's to finish. The sweeper here is a second process, with its own engine.
    @Test
    fun `a provision whose process died is finished by the stranded queue once`() =
        runBlocking {
            val dying = FirstWriteAfterTheGrant(repository) { throw ProcessDied() }
            val payments = CountingPayments(MockPaymentGateway())
            val engine = engine(repository = dying, grants = GrantsThatReport(counters, dying), payments = payments)

            val orderId = startHome(engine)
            confirm(engine, orderId)

            assertEquals(1, dying.fired, "the process never died — nothing was tested")
            assertEquals(PetichStatus.PROCESSING, repository.findById(orderId)?.status)

            val sweeper =
                SuspendedPetichSweeper(
                    repository = repository,
                    engine = engine(payments = payments),
                    clock = clock.asPetichClock(),
                    // The server's own number, and asserted against the bound petich puts on it.
                    stuckAfter = MockPaymentGateway.STRANDED_AFTER,
                )
            val execution = MockPaymentGateway.EXECUTION_PHASE_TIMEOUT.inWholeMilliseconds
            val longestMember = (PetichEngineConfig().phaseTimeoutsMs.values + execution).max()
            assertTrue(
                MockPaymentGateway.STRANDED_AFTER.inWholeMilliseconds > longestMember,
                "a slow member would be taken for a dead one",
            )
            assertEquals(0, sweeper.sweepStuck(), "a saga inside its window was taken as stranded")
            clock.advance(MockPaymentGateway.STRANDED_AFTER + 1.minutes)
            assertEquals(1, sweeper.sweepStuck(), "the stranded queue did not pick the saga up")

            assertEquals(2, payments.settled, "provision ran once — nothing here was run again")
            assertBoughtOnce(orderId, OrderStatus.of(assertNotNull(repository.findById(orderId)).status))
        }

    // THE CASE B-130 WAS OPENED FOR: the home grant commits and its answer is lost, so `Provision`
    // throws having reached nothing it would have recorded. The rollback must leave the counters where
    // the EARLIER purchase left them — not its allowance plus this one's.
    @Test
    fun `an allowance whose answer was lost is taken back`() =
        runBlocking {
            buyHomeOnce()
            val grants = AnswerLostAfterTheGrant(counters)
            val engine = engine(grants = grants)

            val after = confirm(engine, startHome(engine)).getOrThrow()

            assertEquals(OrderStatus.COMPENSATED, after.status)
            assertEquals(1, grants.landed, "the grant never landed — nothing was tested")
            assertAllowance(dataMb, minutes, messages, "a grant whose answer was lost stayed with the subscriber")
            assertEquals(opening - price, balance(), "the second purchase kept its money")
        }

    // ITS CONTROL, and the reason the undo cannot simply take the plan's numbers back: a grant that
    // never landed has nothing under its order, and taking the plan's amounts away anyway would take
    // them out of the EARLIER purchase's allowance.
    @Test
    fun `an allowance that never landed takes nothing from an earlier plan`() =
        runBlocking {
            buyHomeOnce()
            val engine = engine(grants = GrantThatNeverLands(counters))

            val after = confirm(engine, startHome(engine)).getOrThrow()

            assertEquals(OrderStatus.COMPENSATED, after.status)
            assertAllowance(dataMb, minutes, messages, "a rollback took an earlier plan's allowance")
            assertEquals(opening - price, balance(), "the second purchase kept its money")
        }

    private suspend fun startHome(engine: PetichEngine): String {
        val started = StartPurchaseUseCase(engine, repository, plans, balances)(params()).getOrThrow()
        assertEquals(OrderStatus.AWAITING_CONFIRMATION, started.status)
        return started.orderId
    }

    private suspend fun confirm(
        engine: PetichEngine,
        orderId: String,
    ) = ConfirmPurchaseUseCase(engine, repository, balances)(ConfirmPurchaseUseCase.Params(orderId, subscriberId))

    // An earlier purchase of the same bundle, through the same saga, so the counters a rollback must
    // leave alone are ones a real purchase wrote.
    private suspend fun buyHomeOnce() {
        val engine = engine()
        val orderId = startHome(engine)
        assertEquals(OrderStatus.COMPLETED, confirm(engine, orderId).getOrThrow().status)
        assertAllowance(dataMb, minutes, messages, "the earlier purchase granted something else")
    }

    // What one purchase leaves behind, read off every place it writes.
    private suspend fun assertBoughtOnce(
        orderId: String,
        status: OrderStatus,
    ) {
        assertEquals(OrderStatus.COMPLETED, status, "a purchase that was provisioned was rolled back")
        assertEquals(opening - price, balance(), "the subscriber was charged other than once")
        assertEquals(1, ledgerEntries(orderId, LedgerEntryTable.HOLD))
        assertEquals(1, ledgerEntries(orderId, LedgerEntryTable.CAPTURE), "the capture was not written exactly once")
        assertEquals(0, ledgerEntries(orderId, LedgerEntryTable.RELEASE), "the hold of a completed purchase came back")
        assertEquals(Entitlement.ACTIVE, entitlements.findByOrder(orderId)?.status)
        assertAllowance(dataMb, minutes, messages, "the allowance was not granted exactly once")
        assertEquals(listOf("$orderId:purchase.completed"), outboxIds())
    }

    private suspend fun assertAllowance(
        data: Long,
        minutes: Long,
        messages: Long,
        message: String,
    ) {
        assertEquals(
            listOf(data, minutes, messages),
            listOf(UsageCounter.Kind.DATA, UsageCounter.Kind.MINUTES, UsageCounter.Kind.MESSAGES).map { kind ->
                counters.find(subscriberId, kind)?.limitUnits ?: 0
            },
            message,
        )
    }

    private fun params() = StartPurchaseUseCase.Params(subscriberId, homePlan)

    // The stranded queue's claim, as petich writes it: the row as it stands, one version on.
    private suspend fun touch(sagaId: String) {
        val stored = repository.findById(sagaId) ?: error("no saga $sagaId to touch")
        check(repository.update(stored.copy(version = stored.version + 1))) { "the touch itself lost a race" }
    }

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

    // How a process stops, as far as the engine can tell: nothing it catches. petich turns an
    // Exception into a rollback, so an Exception here would be a failure rather than a death.
    private class ProcessDied : Error("the process stopped before the saga was written")

    // The saga store, interrupted once: the first write after a grant has landed — which is the
    // write that moves the saga past `provision` — runs `interrupt` before it goes through.
    private class FirstWriteAfterTheGrant(
        private val real: OutboxAwarePetichRepository,
        private val interrupt: suspend (Petich) -> Unit,
    ) : OutboxAwarePetichRepository {
        var granted = false
        var fired = 0

        override suspend fun findById(id: String): Petich? = real.findById(id)

        override suspend fun saveOrGet(petich: Petich): Petich = real.saveOrGet(petich)

        override suspend fun update(petich: Petich): Boolean = update(petich, emptyList())

        override suspend fun update(
            petich: Petich,
            outboxEvents: List<OutboxEvent>,
        ): Boolean {
            if (granted && fired == 0) {
                fired++
                interrupt(petich)
            }
            return real.update(petich, outboxEvents)
        }
    }

    // The real grant, reported to the store above once it has landed.
    private class GrantsThatReport(
        private val real: UsageGrants,
        private val store: FirstWriteAfterTheGrant,
    ) : UsageGrants by real {
        override suspend fun grantPlanAllowance(
            orderId: String,
            subscriberId: String,
            dataMb: Long,
            minutes: Long,
            messages: Long,
        ) {
            real.grantPlanAllowance(orderId, subscriberId, dataMb, minutes, messages)
            store.granted = true
        }
    }

    // The real provider, counted: settling is the first thing `provision` does.
    private class CountingPayments(
        private val real: PaymentGateway,
    ) : PaymentGateway {
        var settled = 0

        override suspend fun settle(
            orderId: String,
            amount: Money,
        ): PaymentGateway.Settlement {
            settled++
            return real.settle(orderId, amount)
        }
    }

    // The grant commits, and the caller hears a failure instead of the answer.
    private class AnswerLostAfterTheGrant(
        private val real: UsageGrants,
    ) : UsageGrants by real {
        var landed = 0

        override suspend fun grantPlanAllowance(
            orderId: String,
            subscriberId: String,
            dataMb: Long,
            minutes: Long,
            messages: Long,
        ) {
            real.grantPlanAllowance(orderId, subscriberId, dataMb, minutes, messages)
            landed++
            error("the connection dropped after the grant committed")
        }
    }

    // The grant never reaches the database at all.
    private class GrantThatNeverLands(
        real: UsageGrants,
    ) : UsageGrants by real {
        override suspend fun grantPlanAllowance(
            orderId: String,
            subscriberId: String,
            dataMb: Long,
            minutes: Long,
            messages: Long,
        ): Unit = error("the database did not answer")
    }
}
