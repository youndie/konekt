package io.konekt.petich

import io.konekt.db.tables.SagaSweepClaimTable
import io.konekt.time.KonektClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.slf4j.LoggerFactory
import ru.workinprogress.petich.ExpiringPetichRepository
import ru.workinprogress.petich.Petich
import ru.workinprogress.petich.PetichRepository
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

// THE SWEEPER'S REPOSITORY, WITH A CLAIM IN FRONT OF IT.
//
// `B-64` found a purchase abandoned at its confirmation being refunded once per running replica and
// closed it at the invariant: a unique index on `ledger_entry (order_id, kind)`, the entry written
// before the balance moves, and `23505` swallowed because a second compensation is not an error. The
// money has been correct under any number of sweepers ever since — and each of them still walks the
// same sagas, opens the same transactions, calls the same compensation chain and reaches the same
// violation.
//
// That costs nothing on this build and is not free where a reference is read from: a compensation
// that talked to an external system would call it twice — the payment mock is idempotent by
// construction and a real PSP's `refund` is not — and the counter `PetichEngineMetrics` exposes
// reports one number per replica for one reversal.
//
// A DECORATOR AND NOT A FORK. `SuspendedPetichSweeper` and `ExposedPetichRepository` are both
// petich's, and nothing upstream is forked here (D9): what konekt owns is which repository the
// sweeper is handed, so the claim goes in `findExpired` — the one call that decides what this replica
// is about to work on.
class ClaimedSweep(
    private val delegate: ExpiringPetichRepository,
    private val database: Database,
    private val clock: KonektClock,
    // HOW LONG A CLAIM HOLDS. A sweeper that wins and then dies mid-compensation must not hold the
    // saga for ever, so this is a lease: after it, the saga is claimable again. The failure mode is
    // "compensated late" rather than "never", and the unique index is what makes the retry harmless.
    private val lease: Duration = 5.minutes,
) : ExpiringPetichRepository,
    PetichRepository by delegate {
    private val logger = LoggerFactory.getLogger("io.konekt.petich.sweep")

    override suspend fun findExpired(
        nowEpochMs: Long,
        limit: Int,
    ): List<Petich> {
        val expired = delegate.findExpired(nowEpochMs, limit)
        if (expired.isEmpty()) return expired

        val mine = expired.filter { claim(it.id) }

        // THE LOSER IS OBSERVABLE, which is the half that keeps this honest: a claim that never wins
        // looks exactly like one that never runs. Logged with both numbers so a reader can tell "no
        // sagas expired" from "another replica took them all".
        if (mine.size != expired.size) {
            logger.info(
                "swept {} of {} expired sagas; {} were claimed by another replica",
                mine.size,
                expired.size,
                expired.size - mine.size,
            )
        }
        return mine
    }

    // ONE ROW, ONE CONDITIONAL WRITE. `insertIgnore` is the whole arbitration: the primary key on
    // `saga_id` decides, in the database, which caller proceeds — the same shape the refresh-token
    // rotation uses, and not a lock, a leader election or an advisory anything.
    private suspend fun claim(sagaId: String): Boolean =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                val now = clock.now().toEpochMilliseconds()

                // EXPIRED CLAIMS GO FIRST, in the same transaction, so the insert below is the only
                // decision. Doing it as an UPDATE-if-old instead would need the row to exist already,
                // and would answer "did I win" from an affected-row count that is also 1 for a claim
                // this caller had taken a moment ago.
                SagaSweepClaimTable.deleteWhere {
                    (SagaSweepClaimTable.sagaId eq sagaId) and
                        (SagaSweepClaimTable.claimedAt lessEq now - lease.inWholeMilliseconds)
                }

                // `insertIgnore` answers a null id on a conflict rather than throwing, which is
                // exactly the question being asked: did this replica get it.
                SagaSweepClaimTable
                    .insertIgnore {
                        it[SagaSweepClaimTable.sagaId] = sagaId
                        it[claimedAt] = now
                    }.insertedCount > 0
            }
        }
}
