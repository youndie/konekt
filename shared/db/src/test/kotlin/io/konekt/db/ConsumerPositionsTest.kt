package io.konekt.db

import io.konekt.db.tables.SubscriberTable
import io.konekt.testing.PostgresHarness
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

// booblik's recipe as konekt runs it (B-134): the position moves in the transaction of the effects it
// covers, first, and only from the value the batch was read at.
@OptIn(ExperimentalUuidApi::class)
class ConsumerPositionsTest {
    private val db = PostgresHarness.database
    private val positions = ConsumerPositions(db)
    private val key = ConsumerPositions.Key("test-consumer", "usage", 0)

    @BeforeTest
    fun clean() = PostgresHarness.truncateAll()

    // What a repository does: its own `suspendTransaction` on the same database, exactly the shape of
    // every `dbQuery` in the feature modules. Writing through it rather than inline is the point — an
    // effect written inline in the outer block would share the transaction whatever Exposed does with
    // nesting, and prove nothing about the repositories the consumer actually calls.
    private suspend fun repositoryInserts(id: String) =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = db) {
                SubscriberTable.insert {
                    it[SubscriberTable.id] = id
                    it[msisdn] = "1555" + (1_000_000..9_999_999).random()
                    it[createdAt] = 0
                }
            }
        }

    private fun subscriberExists(id: String): Boolean =
        transaction(db) { SubscriberTable.selectAll().where { SubscriberTable.id eq id }.count() == 1L }

    @Test
    fun `a stored position is not overwritten by a later seed`() =
        runBlocking {
            assertEquals(100, positions.seed(key, 100))
            assertEquals(100, positions.seed(key, 7), "a second process starting later moved the position")
            assertEquals(100, positions.load(key))
        }

    @Test
    fun `the position moves only from the value it is stored at`() =
        runBlocking {
            positions.seed(key, 100)
            assertTrue(positions.advance(key, 100, 110))
            var ran = false
            assertFalse(positions.advance(key, 100, 110) { ran = true }, "the same batch was applied twice")
            assertFalse(ran, "the effects ran for a batch that was already applied")
            assertEquals(110, positions.load(key))
        }

    // THE ONE THAT MATTERS: a repository's own transaction joins the position's. An effect that
    // committed on its own would survive the rollback below, and the batch would be applied again on
    // top of it after the restart — the double decrement this item exists to remove.
    //
    // Values are read OUTSIDE the transaction, after it ended: a check inside would see its own
    // uncommitted write whether or not the two were one transaction.
    @Test
    fun `an effect written through a repository rolls back with the position`() =
        runBlocking {
            positions.seed(key, 100)
            val id = Uuid.random().toString()

            assertFailsWith<IllegalStateException> {
                positions.advance(key, 100, 110) {
                    repositoryInserts(id)
                    error("the process dies before the commit")
                }
            }

            assertFalse(subscriberExists(id), "the repository committed on its own: its write survived the rollback")
            assertEquals(100, positions.load(key), "the position moved although its batch rolled back")
        }

    @Test
    fun `an effect written through a repository commits with the position`() =
        runBlocking {
            positions.seed(key, 100)
            val id = Uuid.random().toString()

            assertTrue(positions.advance(key, 100, 110) { repositoryInserts(id) })

            assertTrue(subscriberExists(id))
            assertEquals(110, positions.load(key))
        }

    // Two readers of one batch, really concurrent: the first holds its transaction open inside the
    // effects; the second must wait on the row and then find it moved — not apply the batch beside it.
    @Test
    fun `of two readers of one batch exactly one applies it`() =
        runBlocking {
            positions.seed(key, 100)
            val firstInside = CompletableDeferred<Unit>()
            val letFirstCommit = CompletableDeferred<Unit>()
            val applied = mutableListOf<String>()

            val first =
                async(Dispatchers.IO) {
                    positions.advance(key, 100, 110) {
                        repositoryInserts(Uuid.random().toString())
                        applied += "first"
                        firstInside.complete(Unit)
                        letFirstCommit.await()
                    }
                }
            firstInside.await()
            val second =
                async(Dispatchers.IO) {
                    positions.advance(key, 100, 110) { applied += "second" }
                }
            // The second is now blocked on the row lock; give it time to be, then let the first commit.
            delay(500)
            assertFalse(second.isCompleted, "the second reader did not wait for the first one's row lock")
            letFirstCommit.complete(Unit)

            assertTrue(first.await())
            assertFalse(second.await(), "the second reader applied a batch the first had just applied")
            assertEquals(listOf("first"), applied)
            assertEquals(110, positions.load(key))
        }
}
