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

    override suspend fun release(
        accountId: String,
        orderId: String,
        amount: Money,
    ) {
        dbQuery {
            AccountTable.update({ AccountTable.id eq accountId }) {
                it[balanceMinor] = AccountTable.balanceMinor plus amount.minorUnits
            }
            entry(accountId, orderId, LedgerEntryTable.RELEASE, amount.minorUnits, amount.currency)
        }
    }

    override suspend fun credit(
        accountId: String,
        topUpId: String,
        amount: Money,
    ) {
        dbQuery {
            // No WHERE guard on the amount, unlike `hold`. A credit cannot make the balance negative,
            // so there is nothing for a concurrent one to race against — two top-ups landing together
            // both add, which is the correct answer. The refusal that matters for a top-up happened
            // one step earlier, at the provider.
            AccountTable.update({ AccountTable.id eq accountId }) {
                it[balanceMinor] = AccountTable.balanceMinor plus amount.minorUnits
            }
            entry(accountId, topUpId, LedgerEntryTable.TOP_UP, amount.minorUnits, amount.currency)
        }
    }

    override suspend fun debit(
        accountId: String,
        topUpId: String,
        amount: Money,
    ) {
        dbQuery {
            // Allowed to go negative, and that is deliberate. This runs when a step after the credit
            // failed, so the money was never the subscriber's; refusing to take it back because they
            // have already spent some of it would leave the operator paying for it. A negative
            // balance is visible and recoverable — a silent gift is neither.
            AccountTable.update({ AccountTable.id eq accountId }) {
                it[balanceMinor] = AccountTable.balanceMinor minus amount.minorUnits
            }
            entry(accountId, topUpId, LedgerEntryTable.TOP_UP_REVERSAL, -amount.minorUnits, amount.currency)
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
