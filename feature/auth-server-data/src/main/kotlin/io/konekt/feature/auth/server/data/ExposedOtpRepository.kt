package io.konekt.feature.auth.server.data

import io.konekt.feature.auth.server.domain.Msisdn
import io.konekt.feature.auth.server.domain.OtpChallenge
import io.konekt.feature.auth.server.domain.OtpRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import kotlin.time.Instant

class ExposedOtpRepository(
    private val db: Database,
) : OtpRepository {
    // Dispatchers.IO is load-bearing, not decoration. JDBC blocks, and without this the transaction
    // runs on whichever dispatcher called it — for a route, the engine's own threads. An engine
    // thread stuck in JDBC cannot accept connections, so the symptom under load is clients timing out
    // on connect rather than merely slow responses.
    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        withContext(Dispatchers.IO) { suspendTransaction(db = db) { block() } }

    override suspend fun find(msisdn: Msisdn): OtpChallenge? =
        dbQuery {
            OtpChallengeTable
                .selectAll()
                .where { OtpChallengeTable.msisdn eq msisdn.value }
                .singleOrNull()
                ?.toDomain()
        }

    override suspend fun put(challenge: OtpChallenge) {
        dbQuery {
            // upsert rather than delete-then-insert: two requests for one number arriving together
            // would otherwise race between the two statements, and what they race over is which code
            // the subscriber was actually sent.
            OtpChallengeTable.upsert {
                it[msisdn] = challenge.msisdn.value
                it[codeHash] = challenge.codeHash
                it[issuedAt] = challenge.issuedAt.toEpochMilliseconds()
                it[expiresAt] = challenge.expiresAt.toEpochMilliseconds()
                it[attemptsUsed] = challenge.attemptsUsed
                it[lockedUntil] = challenge.lockedUntil?.toEpochMilliseconds()
            }
        }
    }

    override suspend fun recordFailedAttempt(
        msisdn: Msisdn,
        lockUntil: Instant?,
    ): OtpChallenge? =
        dbQuery {
            // The increment happens in SQL rather than by reading, adding one and writing back. Two
            // wrong guesses arriving together would otherwise both read the same count and both write
            // the same successor, and the budget they are spending would have gained an attempt.
            OtpChallengeTable.update({ OtpChallengeTable.msisdn eq msisdn.value }) {
                it[attemptsUsed] = OtpChallengeTable.attemptsUsed plus 1
                if (lockUntil != null) {
                    it[lockedUntil] = lockUntil.toEpochMilliseconds()
                }
            }

            OtpChallengeTable
                .selectAll()
                .where { OtpChallengeTable.msisdn eq msisdn.value }
                .singleOrNull()
                ?.toDomain()
        }

    override suspend fun clear(msisdn: Msisdn) {
        dbQuery {
            OtpChallengeTable.deleteWhere { OtpChallengeTable.msisdn eq msisdn.value }
        }
    }

    private fun ResultRow.toDomain(): OtpChallenge =
        OtpChallenge(
            msisdn = Msisdn.parse(this[OtpChallengeTable.msisdn]),
            codeHash = this[OtpChallengeTable.codeHash],
            issuedAt = Instant.fromEpochMilliseconds(this[OtpChallengeTable.issuedAt]),
            expiresAt = Instant.fromEpochMilliseconds(this[OtpChallengeTable.expiresAt]),
            attemptsUsed = this[OtpChallengeTable.attemptsUsed],
            lockedUntil = this[OtpChallengeTable.lockedUntil]?.let(Instant::fromEpochMilliseconds),
        )
}
