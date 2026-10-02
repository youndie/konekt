package io.konekt.feature.purchase.server.data

import io.konekt.db.tables.AccountTable
import io.konekt.domain.Currency
import io.konekt.domain.Money
import io.konekt.feature.purchase.server.domain.AccountBalances
import io.konekt.feature.purchase.server.domain.AccountSnapshot
import io.konekt.feature.purchase.server.domain.Entitlement
import io.konekt.feature.purchase.server.domain.Entitlements
import io.konekt.feature.purchase.server.domain.Reversal
import io.konekt.time.KonektClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.minus
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class ExposedAccountBalances(
    private val db: Database,
    private val clock: KonektClock,
) : AccountBalances {
    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        withContext(Dispatchers.IO) { suspendTransaction(db = db) { block() } }

    override suspend fun findAccountOf(subscriberId: String): AccountSnapshot? =
        dbQuery {
            AccountTable
                .selectAll()
                .where { AccountTable.subscriberId eq subscriberId }
                .singleOrNull()
                ?.let {
                    AccountSnapshot(
                        id = it[AccountTable.id],
                        balance =
                            Money(
                                it[AccountTable.balanceMinor],
                                Currency.valueOf(it[AccountTable.currency].trim()),
                            ),
                    )
                }
        }

    // HOLDING AN ORDER THAT IS ALREADY HELD IS NOT A SECOND HOLD, AND NOT A REFUSAL EITHER (B-131).
    //
    // petich re-runs a member whose process died before its next write — the stranded queue picks the
    // saga up on another replica — so `HoldFunds` can arrive here for an order whose hold committed on
    // the first pass. Two answers were wrong. With room on the balance for the price twice, the second
    // HOLD entry hit `(order_id, kind)`, the member threw, and the purchase was rolled back. With room
    // for it once, the WHERE clause refused, the member read that as a balance too low and REFUSED the
    // order — and petich does not undo a member that refuses, so the first pass's money stayed held
    // under a rejected order for good. Both are answered by the ledger: a HOLD under this order is
    // this hold, so the answer is `true` and nothing moves.
    override suspend fun hold(
        accountId: String,
        orderId: String,
        amount: Money,
    ): Boolean =
        try {
            dbQuery {
                // THE REFUSAL IS THE WHERE CLAUSE. Two purchases started together both pass a read
                // followed by a check in Kotlin, and what they overspend is real money. Only one can
                // satisfy `balance_minor >= amount` inside the UPDATE, and the row count is the answer.
                val moved =
                    AccountTable.update({
                        (AccountTable.id eq accountId) and (AccountTable.balanceMinor greaterEq amount.minorUnits)
                    }) {
                        it[balanceMinor] = AccountTable.balanceMinor minus amount.minorUnits
                    }

                // A SECOND HOLD OF THE SAME ORDER fails here, on the unique index, and the exception
                // rolls the UPDATE above back with it — one transaction, so the balance moves once.
                if (moved == 1) {
                    entry(accountId, orderId, LedgerEntryTable.HOLD, -amount.minorUnits, amount.currency)
                }
                // A REFUSAL FOR AN ORDER THAT IS ALREADY HELD is the first hold having taken the room.
                // Asked after the UPDATE, so a hold of this order committing meanwhile is seen: the
                // UPDATE waited on its row lock, and this statement reads what it committed.
                moved == 1 || recorded(orderId, LedgerEntryTable.HOLD)
            }
        } catch (violation: ExposedSQLException) {
            if (violation.sqlState != UNIQUE_VIOLATION) throw violation
            true
        }

    // RETURNING A HOLD, AT MOST ONCE, and it used to be once per caller.
    //
    // Nothing claims an expired saga before compensating it, so every process running a sweeper
    // compensated the same abandoned order — two contours on one database here, two replicas in a
    // deployment — and each one gave the money back. Read out of a running stand: one `hold` of -900
    // and two `release`s of +900 two milliseconds apart, with the account and the ledger agreeing on
    // a subscriber $9 richer than they paid in (`B-64`).
    //
    // THE ORDER OF THE TWO STATEMENTS IS THE FIX. The ledger entry goes first, under a unique index
    // on `(order_id, kind)`; a second attempt violates it, the exception rolls the whole transaction
    // back, and the balance is never touched. Written the other way round the balance would move and
    // then be rolled back too — the same outcome by luck rather than by construction, and only while
    // both statements stay in one transaction.
    //
    // The violation is SWALLOWED and not rethrown: a second compensation of the same order is not an
    // error to report, it is work that was already done. What would be an error is doing it twice.
    override suspend fun release(
        accountId: String,
        orderId: String,
        amount: Money,
    ): Boolean {
        // Set inside the transaction, before the entry that may collide: a second release of the
        // same order reaches the unique index and is swallowed below, and the answer it owes is
        // still "this order's hold is back" — an earlier pass returned it.
        var held = false
        alreadyDoneIsNotAFailure {
            dbQuery {
                // THE LEDGER IS THE ONLY GUARD, AND THE ONLY ONE THAT CAN SEE. `hold` writes its entry
                // only when the UPDATE moved a row, and petich compensates a member whose `hold` itself
                // threw — so a RELEASE without a HOLD would ADD money that was never taken, which is
                // the mirror of `konekt#48` and the more expensive direction of it.
                //
                // `HoldFunds.compensate` calls this unconditionally, and that is the point. Its own
                // step record cannot answer "did the hold land": the record is written by the member
                // after the call returns, and a hold that committed and lost its answer — or whose
                // entitlement write threw — never reached it (youndie/petich B-43). This row is
                // written by the hold, in the hold's own transaction, so it exists exactly when the
                // money moved.
                if (!recorded(orderId, LedgerEntryTable.HOLD)) return@dbQuery
                held = true
                entry(accountId, orderId, LedgerEntryTable.RELEASE, amount.minorUnits, amount.currency)
                AccountTable.update({ AccountTable.id eq accountId }) {
                    it[balanceMinor] = AccountTable.balanceMinor plus amount.minorUnits
                }
            }
        }
        return held
    }

    override suspend fun credit(
        accountId: String,
        topUpId: String,
        amount: Money,
    ) {
        // The same protection as `release`, and for a reason that has not bitten yet: the top-up saga
        // never suspends, so no sweeper reaches it. It is symmetric because the invariant is about the
        // LEDGER rather than about which saga happens to be racing today, and because the index the
        // guard rests on already covers this kind.
        alreadyDoneIsNotAFailure {
            dbQuery {
                // The entry first, so a duplicate rolls the balance back with it — see `release`.
                //
                // No WHERE guard on the amount, unlike `hold`. A credit cannot make the balance
                // negative, so there is nothing for a concurrent one to race against — two top-ups
                // landing together both add, which is the correct answer. The refusal that matters
                // for a top-up happened one step earlier, at the provider.
                entry(accountId, topUpId, LedgerEntryTable.TOP_UP, amount.minorUnits, amount.currency)
                AccountTable.update({ AccountTable.id eq accountId }) {
                    it[balanceMinor] = AccountTable.balanceMinor plus amount.minorUnits
                }
            }
        }
    }

    override suspend fun debit(
        accountId: String,
        topUpId: String,
        amount: Money,
    ) {
        alreadyDoneIsNotAFailure {
            dbQuery {
                // NOTHING TO TAKE BACK IF NOTHING WAS GIVEN, and this guard is not defensive
                // programming — it is the difference between two compensations that look identical
                // from here. `CollectFunds.execute` settles at the provider BEFORE it credits, so a
                // settle that throws — a gateway timeout, a reset connection, a 502 — leaves no
                // `TOP_UP` behind. petich `0.3.0` compensates the step that threw as well as the steps
                // below it (youndie/petich#59), because the engine cannot tell an effect that reached
                // the far side from a call that never landed; without a guard that arrives here as a
                // `TOP_UP_REVERSAL` against a top-up with no `TOP_UP`, and a balance dropping by an
                // amount nobody ever added (`konekt#48`). `CollectFunds.compensate` now asks its own
                // step record (`Credited`) first; this is the ledger asking again, for any caller.
                //
                // `alreadyDoneIsNotAFailure` does not cover it: that makes a SECOND reversal
                // harmless, and this is a first one that should not happen.
                //
                // The ledger is the record and the record is the authority, which is the same shape
                // the rest of this file uses — the unique index on `(order_id, kind)` is what makes
                // the question answerable at all. Undo what the record says happened; return quietly
                // when there is no record.
                if (!recorded(topUpId, LedgerEntryTable.TOP_UP)) return@dbQuery

                // The entry first, so a duplicate rolls the balance back with it — see `release`.
                //
                // Allowed to go negative, and that is deliberate. This runs when a step after the
                // credit failed, so the money was never the subscriber's; refusing to take it back
                // because they have already spent some of it would leave the operator paying for it.
                // A negative balance is visible and recoverable — a silent gift is neither.
                entry(accountId, topUpId, LedgerEntryTable.TOP_UP_REVERSAL, -amount.minorUnits, amount.currency)
                AccountTable.update({ AccountTable.id eq accountId }) {
                    it[balanceMinor] = AccountTable.balanceMinor minus amount.minorUnits
                }
            }
        }
    }

    override suspend fun capture(
        accountId: String,
        orderId: String,
        amount: Money,
    ) {
        // A SECOND CAPTURE OF THE SAME ORDER IS ONE THAT ALREADY HAPPENED, and swallowed for the reason
        // `release` swallows its own. petich writes a member's position after its body returns, so a
        // conflict on that write runs `Provision` again; a plain insert here then hit the unique index,
        // the member threw, and the engine rolled back a purchase the first run had completed — money
        // returned, entitlement cancelled, the allowance left with the subscriber (`B-130`,
        // `ProvisionByOrderTest`).
        alreadyDoneIsNotAFailure {
            dbQuery {
                // No balance movement: the money left at hold time. This entry is what turns a
                // reservation into a purchase, and it is zero-sum against the hold so that a sum over
                // the ledger still equals the balance.
                entry(accountId, orderId, LedgerEntryTable.CAPTURE, 0, amount.currency)
            }
        }
    }

    override suspend fun recordDecline(
        accountId: String,
        orderId: String,
        amount: Money,
        reason: String,
    ) {
        // A REFUSAL RECORDED TWICE IS ONE REFUSAL (B-131). A member that refuses records why and then
        // ends the saga; a process that dies between the two leaves the saga to the stranded queue,
        // which runs the member again and refuses again — and a second DECLINE under
        // `(order_id, kind)` made the refusal throw, so a purchase refused for its balance ended
        // `compensated`. The first reason stays: it is the same refusal.
        alreadyDoneIsNotAFailure {
            dbQuery {
                entry(accountId, orderId, LedgerEntryTable.DECLINE, 0, amount.currency, reason)
            }
        }
    }

    override suspend fun declineReason(orderId: String): String? =
        dbQuery {
            LedgerEntryTable
                .selectAll()
                .where { (LedgerEntryTable.orderId eq orderId) and (LedgerEntryTable.kind eq LedgerEntryTable.DECLINE) }
                .singleOrNull()
                ?.get(LedgerEntryTable.note)
        }

    override suspend fun reversalOf(orderId: String): Reversal? =
        dbQuery {
            LedgerEntryTable
                .selectAll()
                .where { (LedgerEntryTable.orderId eq orderId) and (LedgerEntryTable.kind eq LedgerEntryTable.RELEASE) }
                .singleOrNull()
                ?.let {
                    Reversal(
                        amount =
                            Money(
                                it[LedgerEntryTable.amountMinor],
                                Currency.valueOf(it[LedgerEntryTable.currency].trim()),
                            ),
                        at = Instant.fromEpochMilliseconds(it[LedgerEntryTable.createdAt]),
                    )
                }
        }

    override suspend fun balanceOf(accountId: String): Money? =
        dbQuery {
            AccountTable
                .selectAll()
                .where { AccountTable.id eq accountId }
                .singleOrNull()
                ?.let { Money(it[AccountTable.balanceMinor], Currency.valueOf(it[AccountTable.currency].trim())) }
        }

    // A MOVEMENT THAT WAS ALREADY MADE IS NOT A FAILURE, and the difference is the whole of `B-64`.
    //
    // The unique index on `(order_id, kind)` is what makes a second `release` impossible; this is what
    // makes it QUIET. A compensation that runs twice is ordinary — two sweepers, a retried step — and
    // the second one has nothing to do, so it must not surface as an error a caller has to decide
    // about. What must never happen is the work being done twice, and that is the index's job.
    //
    // CAUGHT BY NAME AND NARROWLY. `ExposedSQLException` wraps whatever the driver threw, so the
    // SQLState is read rather than the message: `23505` is the standard code for a unique violation
    // and is the same on every Postgres in every language. Anything else — a broken connection, a
    // constraint that is not this one — is rethrown, because swallowing those is how a balance stops
    // moving with nothing in the log.
    private inline fun alreadyDoneIsNotAFailure(block: () -> Unit) {
        try {
            block()
        } catch (violation: ExposedSQLException) {
            if (violation.sqlState != UNIQUE_VIOLATION) throw violation
        }
    }

    // Is this movement in the record? One row by the index that already exists — `(order_id, kind)`
    // is unique, so this is a point lookup and not a scan.
    private fun recorded(
        orderId: String,
        kind: String,
    ): Boolean =
        LedgerEntryTable
            .selectAll()
            .where { (LedgerEntryTable.orderId eq orderId) and (LedgerEntryTable.kind eq kind) }
            .empty()
            .not()

    private fun entry(
        accountId: String,
        orderId: String,
        kind: String,
        amountMinor: Long,
        currency: Currency,
        note: String? = null,
    ) {
        LedgerEntryTable.insert {
            it[id] = Uuid.random().toString()
            it[LedgerEntryTable.accountId] = accountId
            it[LedgerEntryTable.orderId] = orderId
            it[LedgerEntryTable.kind] = kind
            it[LedgerEntryTable.amountMinor] = amountMinor
            it[LedgerEntryTable.currency] = currency.name
            it[LedgerEntryTable.note] = note
            it[createdAt] = clock.now().toEpochMilliseconds()
        }
    }

    private companion object {
        // SQLState 23505, the standard code for a unique violation. A number rather than a message
        // because the message is the driver's and changes with it.
        const val UNIQUE_VIOLATION = "23505"
    }
}

@OptIn(ExperimentalUuidApi::class)
class ExposedEntitlements(
    private val db: Database,
    private val clock: KonektClock,
) : Entitlements {
    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        withContext(Dispatchers.IO) { suspendTransaction(db = db) { block() } }

    override suspend fun createPending(
        orderId: String,
        subscriberId: String,
        planId: String,
        price: Money,
    ) {
        dbQuery {
            // `insertIgnore` under `uq_entitlement_order_id` (B-131): a `HoldFunds` re-run by the
            // stranded queue finds the first pass's entitlement here, and a plain insert threw and
            // rolled back a purchase that only lost its process. The row is keyed by the order, so
            // the one already there is this one.
            EntitlementTable.insertIgnore {
                it[id] = Uuid.random().toString()
                it[EntitlementTable.orderId] = orderId
                it[EntitlementTable.subscriberId] = subscriberId
                it[EntitlementTable.planId] = planId
                it[status] = Entitlement.PENDING
                it[priceMinor] = price.minorUnits
                it[currency] = price.currency.name
                it[createdAt] = clock.now().toEpochMilliseconds()
            }
        }
    }

    override suspend fun activate(orderId: String) {
        dbQuery {
            EntitlementTable.update({ EntitlementTable.orderId eq orderId }) {
                it[status] = Entitlement.ACTIVE
                it[activatedAt] = clock.now().toEpochMilliseconds()
            }
        }
    }

    override suspend fun cancel(orderId: String) {
        dbQuery {
            // Only from pending or active, so a second compensation cannot resurrect and re-cancel a
            // row — and so that cancelling is idempotent, which a compensation has to be: petich may
            // retry the state write around it.
            EntitlementTable.update({ EntitlementTable.orderId eq orderId }) {
                it[status] = Entitlement.CANCELLED
                it[activatedAt] = null
            }
        }
    }

    override suspend fun findByOrder(orderId: String): Entitlement? =
        dbQuery {
            EntitlementTable
                .selectAll()
                .where { EntitlementTable.orderId eq orderId }
                .singleOrNull()
                ?.let {
                    Entitlement(
                        id = it[EntitlementTable.id],
                        orderId = it[EntitlementTable.orderId],
                        subscriberId = it[EntitlementTable.subscriberId],
                        planId = it[EntitlementTable.planId],
                        status = it[EntitlementTable.status],
                    )
                }
        }
}
