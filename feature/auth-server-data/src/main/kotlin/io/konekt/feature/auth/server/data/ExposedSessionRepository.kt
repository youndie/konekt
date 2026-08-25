package io.konekt.feature.auth.server.data

import io.konekt.feature.auth.server.domain.IssuedRefreshToken
import io.konekt.feature.auth.server.domain.SessionFamily
import io.konekt.feature.auth.server.domain.SessionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class ExposedSessionRepository(
    private val db: Database,
) : SessionRepository {
    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        withContext(Dispatchers.IO) { suspendTransaction(db = db) { block() } }

    override suspend fun openFamily(
        subscriberId: String,
        at: Instant,
    ): SessionFamily =
        dbQuery {
            val familyId = Uuid.random().toString()
            SessionFamilyTable.insert {
                it[id] = familyId
                it[SessionFamilyTable.subscriberId] = subscriberId
                it[createdAt] = at.toEpochMilliseconds()
            }
            SessionFamily(id = familyId, subscriberId = subscriberId, revokedAt = null, revokedReason = null)
        }

    override suspend fun findFamily(id: String): SessionFamily? =
        dbQuery {
            SessionFamilyTable
                .selectAll()
                .where { SessionFamilyTable.id eq id }
                .singleOrNull()
                ?.toFamily()
        }

    override suspend fun recordRefreshToken(token: IssuedRefreshToken) {
        dbQuery {
            RefreshTokenTable.insert {
                it[id] = token.id
                it[familyId] = token.familyId
                it[issuedAt] = token.issuedAt.toEpochMilliseconds()
                it[expiresAt] = token.expiresAt.toEpochMilliseconds()
                it[usedAt] = token.usedAt?.toEpochMilliseconds()
            }
        }
    }

    override suspend fun findRefreshToken(id: String): IssuedRefreshToken? =
        dbQuery {
            RefreshTokenTable
                .selectAll()
                .where { RefreshTokenTable.id eq id }
                .singleOrNull()
                ?.toToken()
        }

    override suspend fun markRefreshTokenUsed(
        id: String,
        at: Instant,
    ): Boolean =
        dbQuery {
            // A CONDITIONAL update, and this is the arbitration the whole design rests on. Two
            // exchanges of one token arriving together both pass a read-then-write in Kotlin; only
            // one of them can satisfy `used_at IS NULL` inside the database. The row count is the
            // answer, and the loser is treated as reuse — which is right, because from here a race
            // and a theft look identical and the safe reading is the second one.
            val updated =
                RefreshTokenTable.update({ (RefreshTokenTable.id eq id) and RefreshTokenTable.usedAt.isNull() }) {
                    it[usedAt] = at.toEpochMilliseconds()
                }
            updated == 1
        }

    override suspend fun revokeFamily(
        id: String,
        reason: String,
        at: Instant,
    ) {
        dbQuery {
            // Only while still alive, so a logout cannot overwrite the record of a detected theft.
            // The first revocation is the one that happened.
            SessionFamilyTable.update({ (SessionFamilyTable.id eq id) and SessionFamilyTable.revokedAt.isNull() }) {
                it[revokedAt] = at.toEpochMilliseconds()
                it[revokedReason] = reason
            }
        }
    }

    private fun ResultRow.toFamily() =
        SessionFamily(
            id = this[SessionFamilyTable.id],
            subscriberId = this[SessionFamilyTable.subscriberId],
            revokedAt = this[SessionFamilyTable.revokedAt]?.let(Instant::fromEpochMilliseconds),
            revokedReason = this[SessionFamilyTable.revokedReason],
        )

    private fun ResultRow.toToken() =
        IssuedRefreshToken(
            id = this[RefreshTokenTable.id],
            familyId = this[RefreshTokenTable.familyId],
            issuedAt = Instant.fromEpochMilliseconds(this[RefreshTokenTable.issuedAt]),
            expiresAt = Instant.fromEpochMilliseconds(this[RefreshTokenTable.expiresAt]),
            usedAt = this[RefreshTokenTable.usedAt]?.let(Instant::fromEpochMilliseconds),
        )
}
