package io.konekt.db

import io.konekt.db.tables.ConsumerPositionTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update

// WHERE A BOOBLIK CONSUMER HAS GOT TO, kept in the database its effects go to (B-134).
//
// booblik's recipe (`feature-consumer-position` in youndie/booblik): the position is updated in the
// SAME transaction as the effects of the batch it covers, FIRST, and only from the value the batch was
// read at. Two readers of one partition then apply each batch once — the second blocks on the row,
// finds it moved when the first commits, updates nothing and rolls back without having done a thing.
// A crash anywhere inside leaves position and effects as they were, and the batch is read again.
//
// Offsets are plain `Long`s here so that this module needs no booblik client: what it stores is a
// number, and the consumer is what knows it is an offset.
class ConsumerPositions(
    private val db: Database,
) {
    // The same shape as every repository's `dbQuery`, and that sameness is the point: a repository
    // called inside [advance]'s block opens `suspendTransaction` too, and joins this one rather than
    // committing on its own — `ConsumerPositionsTest` proves it, because a nested transaction that
    // committed separately would make "one transaction" a sentence.
    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        withContext(Dispatchers.IO) { suspendTransaction(db = db) { block() } }

    data class Key(
        val consumer: String,
        val topic: String,
        val partition: Int,
    )

    suspend fun load(key: Key): Long? =
        dbQuery {
            ConsumerPositionTable
                .selectAll()
                .where { key.matches() }
                .singleOrNull()
                ?.get(ConsumerPositionTable.nextOffset)
        }

    // Writes [at] only when nothing is stored yet, and answers what IS stored — so two processes
    // starting together agree on one first position instead of the later one overwriting the earlier.
    suspend fun seed(
        key: Key,
        at: Long,
    ): Long =
        dbQuery {
            ConsumerPositionTable.insertIgnore {
                it[consumer] = key.consumer
                it[topic] = key.topic
                it[partitionId] = key.partition
                it[nextOffset] = at
            }
            ConsumerPositionTable
                .selectAll()
                .where { key.matches() }
                .single()[ConsumerPositionTable.nextOffset]
        }

    // Moves the position from [from] to [to] and runs [effects], in one transaction, or does neither.
    //
    // Returns false — with [effects] never called — when the stored position is not [from]: another
    // reader applied that batch, or this one is behind what it stored. The caller reads [load] and
    // carries on from there. An exception from [effects] rolls the move back with them.
    suspend fun advance(
        key: Key,
        from: Long,
        to: Long,
        effects: suspend () -> Unit = {},
    ): Boolean =
        dbQuery {
            val moved =
                ConsumerPositionTable.update({ key.matches() and (ConsumerPositionTable.nextOffset eq from) }) {
                    it[nextOffset] = to
                }
            if (moved == 0) return@dbQuery false
            effects()
            true
        }

    private fun Key.matches() =
        (ConsumerPositionTable.consumer eq consumer) and
            (ConsumerPositionTable.topic eq topic) and
            (ConsumerPositionTable.partitionId eq partition)
}
