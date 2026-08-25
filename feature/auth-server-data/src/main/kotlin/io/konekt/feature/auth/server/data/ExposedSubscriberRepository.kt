package io.konekt.feature.auth.server.data

import io.konekt.db.tables.AccountTable
import io.konekt.db.tables.SubscriberTable
import io.konekt.domain.Money
import io.konekt.feature.auth.server.domain.Msisdn
import io.konekt.feature.auth.server.domain.Subscriber
import io.konekt.feature.auth.server.domain.SubscriberRepository
import io.konekt.time.KonektClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

// `subscriber` and `account` are declared once, in :shared:db, because more than one feature reads
// them. A local copy of a table declaration is a second schema that agrees with the first until
// somebody changes one.
class ExposedSubscriberRepository(
    private val db: Database,
    private val clock: KonektClock,
) : SubscriberRepository {
    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        withContext(Dispatchers.IO) { suspendTransaction(db = db) { block() } }

    override suspend fun findByMsisdn(msisdn: Msisdn): Subscriber? =
        dbQuery {
            SubscriberTable
                .selectAll()
                .where { SubscriberTable.msisdn eq msisdn.value }
                .singleOrNull()
                ?.let { Subscriber(id = it[SubscriberTable.id], msisdn = Msisdn.parse(it[SubscriberTable.msisdn])) }
        }

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun createWithAccount(
        msisdn: Msisdn,
        openingBalance: Money,
    ): Subscriber =
        dbQuery {
            // One transaction for both rows, and that is the point of the method existing at all. A
            // subscriber without an account is a row every balance read has to defend against
            // forever, because of one sign-up interrupted at the wrong millisecond.
            val now = clock.now().toEpochMilliseconds()
            val newSubscriberId = Uuid.random().toString()

            SubscriberTable.insert {
                it[id] = newSubscriberId
                it[SubscriberTable.msisdn] = msisdn.value
                it[createdAt] = now
            }

            AccountTable.insert {
                it[id] = Uuid.random().toString()
                // The local is named apart from the column on purpose: `it[subscriberId] =
                // subscriberId` inside this block reads the COLUMN on both sides and assigns it to
                // itself, which compiles in some shapes and is never what anybody meant.
                it[subscriberId] = newSubscriberId
                it[balanceMinor] = openingBalance.minorUnits
                it[currency] = openingBalance.currency.name
                it[createdAt] = now
            }

            Subscriber(id = newSubscriberId, msisdn = msisdn)
        }
}
