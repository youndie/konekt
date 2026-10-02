package io.konekt.feature.purchase.server.data

import io.github.youndie.petich.EnrichedPayload
import io.github.youndie.petich.OutboxEvent
import io.github.youndie.petich.Petich
import io.github.youndie.petich.PetichEngine
import io.github.youndie.petich.PetichEngineConfig
import io.github.youndie.petich.PetichMemberProbe
import io.github.youndie.petich.PetichPayload
import io.github.youndie.petich.PetichSideEffect
import io.github.youndie.petich.PetichStatus
import io.github.youndie.petich.PetichStepContext
import io.github.youndie.petich.PetichStepRecord
import io.github.youndie.petich.SimpleEnrichedPayload
import io.github.youndie.petich.postgres.ExposedPetichRepository
import io.github.youndie.petich.postgres.OutboxEventsTable
import io.github.youndie.petich.postgres.PetichTable
import io.konekt.db.tables.AccountTable
import io.konekt.db.tables.SubscriberTable
import io.konekt.domain.Currency
import io.konekt.domain.Money
import io.konekt.feature.purchase.server.domain.AccountBalances
import io.konekt.feature.purchase.server.domain.CollectFunds
import io.konekt.feature.purchase.server.domain.Credited
import io.konekt.feature.purchase.server.domain.FindTopUpUseCase
import io.konekt.feature.purchase.server.domain.OrderStatus
import io.konekt.feature.purchase.server.domain.PaymentGateway
import io.konekt.feature.purchase.server.domain.StartTopUpUseCase
import io.konekt.feature.purchase.server.domain.TOP_UP_SAGA_TYPE
import io.konekt.feature.purchase.server.domain.TopUpAmount
import io.konekt.feature.purchase.server.domain.TopUpLimits
import io.konekt.feature.purchase.server.domain.TopUpPayload
import io.konekt.feature.purchase.server.domain.topUpPetich
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
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

// The saga that puts money in, against a real Postgres.
//
// `runBlocking` and not `runTest`, for the reason the sibling saga tests carry: the engine wraps each
// step in `withTimeout`, and a virtual clock cancels the first real suspension inside one.
@OptIn(ExperimentalUuidApi::class)
class TopUpSagaTest {
    private val clock = KonektClock { Instant.fromEpochMilliseconds(1_700_000_000_000) }

    private val json =
        Json {
            serializersModule =
                SerializersModule {
                    polymorphic(PetichPayload::class) { subclass(TopUpPayload::class) }
                    polymorphic(EnrichedPayload::class) { subclass(SimpleEnrichedPayload::class) }
                    // The same registration the application makes, and for the same reason the
                    // comment there gives: unregistered means the first top-up that credits
                    // anything cannot be written down at all.
                    polymorphic(PetichStepRecord::class) { subclass(Credited::class) }
                }
        }

    private val repository = ExposedPetichRepository(PostgresHarness.database, PetichTable(json), OutboxEventsTable())
    private val balances = ExposedAccountBalances(PostgresHarness.database, clock)

    private val opening = Money.ofMajor(10, Currency.DEFAULT)
    private val amount = Money.ofMajor(25, Currency.DEFAULT)
    private lateinit var subscriberId: String
    private lateinit var accountId: String

    private fun sagaWith(
        payments: MockPaymentGateway,
        // What the saga's members move money through. The use case around them keeps the real port.
        members: AccountBalances = balances,
    ): StartTopUpUseCase =
        StartTopUpUseCase(
            engine =
                PetichEngine(
                    definitions = listOf(topUpPetich(members, payments, json)),
                    repository = repository,
                    config = PetichEngineConfig(requireOutbox = true),
                    clock = clock.asPetichClock(),
                ),
            topUps = repository,
            balances = balances,
        )

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
                it[msisdn] = "15550107777"
                it[createdAt] = 0
            }
            AccountTable.insert {
                it[id] = newAccountId
                it[AccountTable.subscriberId] = newSubscriberId
                it[balanceMinor] = opening.minorUnits
                it[currency] = opening.currency.name
                it[createdAt] = 0
            }
        }
    }

    @Test
    fun `a top-up the provider takes raises the balance by exactly the amount`() =
        runBlocking {
            val start = sagaWith(MockPaymentGateway(mode = MockPaymentGateway.Mode.APPROVE))

            val view = start(StartTopUpUseCase.Params(subscriberId, TopUpAmount.minor(amount.minorUnits))).getOrThrow()

            assertEquals(OrderStatus.COMPLETED, view.status)
            assertEquals(opening + amount, view.balance)
            assertEquals(opening + amount, balances.balanceOf(accountId))
            assertNull(view.declineReason)
        }

    @Test
    fun `a top-up the provider refuses leaves the balance exactly where it was`() =
        runBlocking {
            val start =
                sagaWith(
                    MockPaymentGateway(
                        mode = MockPaymentGateway.Mode.DECLINE,
                        declineReason = "The provider declined the operation.",
                    ),
                )

            val view = start(StartTopUpUseCase.Params(subscriberId, TopUpAmount.minor(amount.minorUnits))).getOrThrow()

            assertEquals(OrderStatus.COMPENSATED, view.status)
            // The assertion the ordering exists for. A balance raised before the provider confirmed is
            // money the operator has given away, and this is the case that would give it away.
            assertEquals(opening, view.balance)
            assertEquals(opening, balances.balanceOf(accountId))
            assertEquals("The provider declined the operation.", view.declineReason)
        }

    @Test
    fun `an amount below the floor is rejected, and nothing is compensated`() =
        runBlocking {
            val start = sagaWith(MockPaymentGateway(mode = MockPaymentGateway.Mode.APPROVE))

            val view =
                start(
                    StartTopUpUseCase.Params(subscriberId, TopUpAmount.minor(TopUpLimits.MIN_MINOR - 1)),
                ).getOrThrow()

            // REJECTED and not COMPENSATED: a rule refused before anything happened, so there is
            // nothing to undo and no compensating step runs at all.
            assertEquals(OrderStatus.REJECTED, view.status)
            assertEquals(opening, balances.balanceOf(accountId))
            assertNull(view.declineReason, "nothing declined it — a rule refused it")
        }

    @Test
    fun `an amount above the ceiling is rejected`() =
        runBlocking {
            val start = sagaWith(MockPaymentGateway(mode = MockPaymentGateway.Mode.APPROVE))

            val view =
                start(
                    StartTopUpUseCase.Params(subscriberId, TopUpAmount.minor(TopUpLimits.MAX_MINOR + 1)),
                ).getOrThrow()

            assertEquals(OrderStatus.REJECTED, view.status)
            assertEquals(opening, balances.balanceOf(accountId))
        }

    // THE BRANCH NOTHING ELSE CAN REACH, and it is driven directly for exactly that reason.
    //
    // `CollectFunds.compensate` takes the money back when a member AFTER the credit fails.
    // Today the only member after it announces and cannot fail, so no scenario — not the stand, not the
    // saga tests above — ever runs this. It is written because the day a step is added between them,
    // the failure is a subscriber holding money the operator was never paid for, and nothing would
    // have objected.
    @Test
    fun `taking a credit back debits exactly what was credited`(): Unit =
        runBlocking {
            val member = CollectFunds(balances, MockPaymentGateway(mode = MockPaymentGateway.Mode.APPROVE))
            val payload = TopUpPayload(subscriberId = subscriberId, accountId = accountId, amount = amount)
            val saga =
                Petich(
                    id = Uuid.random().toString(),
                    type = TOP_UP_SAGA_TYPE,
                    status = PetichStatus.PROCESSING,
                    payload = payload,
                    enrichedPayload = SimpleEnrichedPayload(),
                )

            val ctx = PetichMemberProbe(saga, stepKey = "collect-funds")
            member.execute(ctx, payload)
            assertEquals(opening + amount, balances.balanceOf(accountId))

            // The undo gets the SAME context, which is how it sees what the action recorded — the
            // engine does this by keeping the record on the saga's row between the two.
            member.compensate(ctx, payload)

            assertEquals(opening, balances.balanceOf(accountId))
            // Both movements are in the ledger under their own kinds, which is what keeps a top-up
            // and its reversal legible a year from now — a RELEASE would have said the money was
            // always the subscriber's.
            assertNotNull(balances.balanceOf(accountId))
        }

    // THE OTHER EXIT FROM THE SAME STEP, and the one no test reached until `konekt#48`.
    //
    // `execute` settles at the provider BEFORE it credits. A decline comes back as a value and
    // ends in `ctx.fail`; a gateway that does not answer at all — a timeout, a reset connection,
    // a 502 — comes back as a THROW, and then the credit never happened. petich `0.3.0` compensates
    // the step that threw as well as the steps below it (youndie/petich#59), because the engine
    // cannot tell an effect that reached the far side from a call that never landed.
    //
    // So this asks the member the question the engine is about to ask it, directly: take back
    // a top-up that was never given. The answer must be nothing at all — not a reversal against a
    // top-up with no `TOP_UP`, and not a balance dropping by an amount nobody added. Written against
    // petich `0.1.0`, where the engine does not yet make this call, so that the upgrade finds the
    // question already answered.
    @Test
    fun `a settle that throws leaves nothing to take back`(): Unit =
        runBlocking {
            val member = CollectFunds(balances, UnreachableGateway)
            val payload = TopUpPayload(subscriberId = subscriberId, accountId = accountId, amount = amount)
            val saga =
                Petich(
                    id = Uuid.random().toString(),
                    type = TOP_UP_SAGA_TYPE,
                    status = PetichStatus.PROCESSING,
                    payload = payload,
                    enrichedPayload = SimpleEnrichedPayload(),
                )

            val ctx = PetichMemberProbe(saga, stepKey = "collect-funds")
            assertFailsWith<IllegalStateException> { member.execute(ctx, payload) }
            assertEquals(opening, balances.balanceOf(accountId))

            member.compensate(ctx, payload)

            assertEquals(opening, balances.balanceOf(accountId))
            assertEquals(
                0,
                ledgerEntries(saga.id, LedgerEntryTable.TOP_UP_REVERSAL),
                "a reversal was recorded against a top-up that never happened",
            )
        }

    // THE OTHER SIDE OF THE HOLD'S FIX, PINNED BECAUSE IT IS A DECISION. A credit that committed and
    // lost its answer leaves no `Credited`, so the undo leaves it standing — the blind spot petich B-43
    // describes, the one `HoldRollbackTest` closes for the purchase. Here it stays: a credit exists
    // only once the provider has settled, nothing in this saga can refund that, and taking the credit
    // back by name would charge the subscriber for nothing. Whoever makes this undo by name has to
    // bring the refund with it, and this test is where they find out.
    @Test
    fun `a credit whose answer was lost stays where the provider was paid for it`(): Unit =
        runBlocking {
            val start =
                sagaWith(
                    MockPaymentGateway(mode = MockPaymentGateway.Mode.APPROVE),
                    members = CreditWhoseAnswerIsLost(balances),
                )

            val view = start(StartTopUpUseCase.Params(subscriberId, TopUpAmount.minor(amount.minorUnits))).getOrThrow()

            // The saga says it failed, which is what petich saw.
            assertEquals(OrderStatus.COMPENSATED, view.status)
            assertEquals(
                1,
                ledgerEntries(view.id, LedgerEntryTable.TOP_UP),
                "the credit never happened — nothing was tested",
            )
            assertEquals(
                opening + amount,
                balances.balanceOf(accountId),
                "a credit the provider was paid for was taken back",
            )
            assertEquals(0, ledgerEntries(view.id, LedgerEntryTable.TOP_UP_REVERSAL))
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

    // The credit commits, and the caller hears a failure instead of the answer.
    private class CreditWhoseAnswerIsLost(
        private val real: AccountBalances,
    ) : AccountBalances by real {
        override suspend fun credit(
            accountId: String,
            topUpId: String,
            amount: Money,
        ) {
            real.credit(accountId, topUpId, amount)
            error("the connection dropped after the credit committed")
        }
    }

    // A provider that does not answer. Not a `MockPaymentGateway.Mode`, because that class is
    // PRODUCTION code — it is what a demonstration runs against — and a mode whose only job is to
    // throw would be a switch nobody may flip on a stand.
    private object UnreachableGateway : PaymentGateway {
        override suspend fun settle(
            orderId: String,
            amount: Money,
        ): PaymentGateway.Settlement = error("the gateway did not answer")
    }
}
