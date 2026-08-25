package io.konekt.feature.esim.server.data

import io.konekt.components.EsimStatuses
import io.konekt.db.tables.EsimTable
import io.konekt.feature.esim.server.domain.EsimProfile
import io.konekt.feature.esim.server.domain.EsimRepository
import io.konekt.time.KonektClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class ExposedEsimRepository(
    private val db: Database,
    private val clock: KonektClock,
) : EsimRepository {
    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        withContext(Dispatchers.IO) { suspendTransaction(db = db) { block() } }

    // A TERMINATED profile does not occupy a slot, and that is the whole content of this query.
    // Counting every row the subscriber has ever had would refuse an eighth profile to somebody
    // holding two, which is the sort of wrong answer that reads as a device limit and is not one.
    override suspend fun countHeldBy(subscriberId: String): Int =
        dbQuery {
            EsimTable
                .selectAll()
                .where {
                    (EsimTable.subscriberId eq subscriberId) and (EsimTable.status neq EsimStatuses.TERMINATED)
                }.count()
                .toInt()
        }

    override suspend fun create(
        subscriberId: String,
        iccid: String,
        activationCode: String,
    ): EsimProfile {
        val newId = Uuid.random().toString()
        val now = clock.now()

        dbQuery {
            EsimTable.insert {
                it[EsimTable.id] = newId
                // A differently named local, because inside `insert { }` the table is the receiver
                // and a bare `subscriberId` would resolve to the COLUMN rather than the parameter.
                // It happens to work here — a parameter wins that resolution where a property does
                // not — and it is spelled out anyway, because the version that does not work looks
                // exactly like this one.
                it[EsimTable.subscriberId] = subscriberId
                it[EsimTable.iccid] = iccid
                // READY and not ORDERED: the code exists, so the profile can be installed right now.
                // ORDERED is the state of a profile the manager has not answered for yet, which this
                // flow never produces because the mock answers at once.
                it[EsimTable.status] = EsimStatuses.READY
                it[EsimTable.activationCode] = activationCode
                it[EsimTable.createdAt] = now.toEpochMilliseconds()
            }
        }

        return EsimProfile(
            id = newId,
            subscriberId = subscriberId,
            iccid = iccid,
            status = EsimStatuses.READY,
            activationCode = activationCode,
            createdAt = now,
        )
    }

    override suspend fun findById(esimId: String): EsimProfile? =
        dbQuery {
            EsimTable
                .selectAll()
                .where { EsimTable.id eq esimId }
                .singleOrNull()
                ?.toDomain()
        }

    // No `activated_at`. Installed is not active — the profile is on the device and the network has
    // not yet seen it — and writing a date into a column that means something else is how a report
    // comes to count the wrong thing.
    override suspend fun markInstalled(esimId: String) {
        dbQuery {
            EsimTable.update({ EsimTable.id eq esimId }) {
                it[EsimTable.status] = EsimStatuses.INSTALLED
            }
        }
    }

    private fun ResultRow.toDomain(): EsimProfile =
        EsimProfile(
            id = this[EsimTable.id],
            subscriberId = this[EsimTable.subscriberId],
            iccid = this[EsimTable.iccid],
            status = this[EsimTable.status],
            activationCode = this[EsimTable.activationCode],
            createdAt = Instant.fromEpochMilliseconds(this[EsimTable.createdAt]),
            activatedAt = this[EsimTable.activatedAt]?.let(Instant::fromEpochMilliseconds),
        )
}
