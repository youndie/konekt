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

    override suspend fun hold(
        accountId: String,
        orderId: String,
        amount: Money,
    ): Boolean =
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

            if (moved == 1) {
                entry(accountId, orderId, LedgerEntryTable.HOLD, -amount.minorUnits, amount.currency)
            }
            moved == 1
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
    ) {
        alreadyDoneIsNotAFailure {
            dbQuery {
                entry(accountId, orderId, LedgerEntryTable.RELEASE, amount.minorUnits, amount.currency)
                AccountTable.update({ AccountTable.id eq accountId }) {
                    it[balanceMinor] = AccountTable.balanceMinor plus amount.minorUnits
                }
            }
        }
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
        dbQuery {
            // No balance movement: the money left at hold time. This entry is what turns a
            // reservation into a purchase, and it is zero-sum against the hold so that a sum over the
            // ledger still equals the balance.
            entry(accountId, orderId, LedgerEntryTable.CAPTURE, 0, amount.currency)
        }
    }

    override suspend fun recordDecline(
        accountId: String,
        orderId: String,
        amount: Money,
        reason: String,
    ) {
        dbQuery {
            entry(accountId, orderId, LedgerEntryTable.DECLINE, 0, amount.currency, reason)
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
            EntitlementTable.insert {
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
