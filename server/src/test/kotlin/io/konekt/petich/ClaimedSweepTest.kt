package io.konekt.petich

import io.konekt.db.tables.SagaSweepClaimTable
import io.konekt.testing.PostgresHarness
import io.konekt.time.KonektClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import ru.workinprogress.petich.ExpiringPetichRepository
import ru.workinprogress.petich.Petich
import ru.workinprogress.petich.PetichPayload
import ru.workinprogress.petich.PetichRepository
import ru.workinprogress.petich.PetichStatus
import ru.workinprogress.petich.SimpleEnrichedPayload
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

// TWO SWEEPERS, ONE SAGA, ONE COMPENSATION ATTEMPT.
//
// `B-64` made the OUTCOME correct under any number of replicas and left the WORK: each sweeper walks
// the same sagas and reaches the same unique-index violation. `B-92` puts a claim in front, and this
// asks the only question that matters about it — when two of them look at the same expired sagas at
// the same time, does each saga come back to exactly one?
//
// AGAINST A REAL POSTGRES AND ON `Dispatchers.IO`, the way `B-64` verified its index. The arbitration
// is `insertIgnore` against a primary key, which is a claim the DATABASE decides; a test over a fake
// would be asserting about a `MutableSet`.
class ClaimedSweepTest {
    private val clock = KonektClock { NOW }

    @BeforeTest
    fun clean() {
        PostgresHarness.truncateAll()
    }

    @Test
    fun `two sweepers racing on the same sagas divide them, with none taken twice`() =
        runBlocking {
            val sagas = (1..20).map { saga("saga-$it") }
            val first = ClaimedSweep(FixedExpired(sagas), PostgresHarness.database, clock)
            val second = ClaimedSweep(FixedExpired(sagas), PostgresHarness.database, clock)

            // BOTH ON `Dispatchers.IO` AND STARTED TOGETHER. Sequential calls would pass on an
            // implementation that read-then-wrote, which is the implementation this exists to refuse.
            val (mine, theirs) =
                withContext(Dispatchers.IO) {
                    listOf(
                        async { first.findExpired(NOW.toEpochMilliseconds(), sagas.size) },
                        async { second.findExpired(NOW.toEpochMilliseconds(), sagas.size) },
                    ).awaitAll()
                }

            val ids = (mine + theirs).map { it.id }
            assertEquals(
                sagas.size,
                ids.size,
                "a saga was taken by neither sweeper or by both: ${mine.map { it.id }} / ${theirs.map { it.id }}",
            )
            assertEquals(ids.size, ids.toSet().size, "the same saga was claimed twice: $ids")

            // AND SOMETHING WAS ACTUALLY CLAIMED. Without this the assertion above is satisfied by
            // two sweepers that both return nothing, which is a correct-looking outcome and would
            // mean no saga is ever compensated at all.
            assertTrue(
                ids.isNotEmpty(),
                "neither sweeper claimed anything, so nothing would ever be compensated",
            )
        }

    // A CLAIM IS A LEASE. A sweeper that wins and then dies mid-compensation must not hold the saga
    // for ever: nobody would retry it, which is worse than the duplicated work the claim removes.
    @Test
    fun `a claim older than the lease is taken again`() =
        runBlocking {
            val sagas = listOf(saga("abandoned"))
            val died =
                ClaimedSweep(FixedExpired(sagas), PostgresHarness.database, KonektClock { NOW }, lease = 5.minutes)
            assertEquals(1, died.findExpired(NOW.toEpochMilliseconds(), 10).size, "the first sweeper did not claim it")

            // A minute later: still held, and the second sweeper correctly does nothing.
            assertEquals(
                emptyList(),
                ClaimedSweep(FixedExpired(sagas), PostgresHarness.database, KonektClock { NOW + 1.minutes }, 5.minutes)
                    .findExpired((NOW + 1.minutes).toEpochMilliseconds(), 10),
                "a live claim was taken from its holder",
            )

            // Six minutes later: the holder is gone and the saga is claimable again.
            assertEquals(
                1,
                ClaimedSweep(FixedExpired(sagas), PostgresHarness.database, KonektClock { NOW + 6.minutes }, 5.minutes)
                    .findExpired((NOW + 6.minutes).toEpochMilliseconds(), 10)
                    .size,
                "a saga whose sweeper died is held for ever, so nothing ever compensates it",
            )

            // One row throughout: the lease is a re-claim, not an accumulation.
            assertEquals(1, claimRows(), "the claim table grew a row per attempt")
        }

    // Nothing expired is not a claim of nothing. The delegate answering an empty list must not write
    // a row — a sweep with nothing to do is the ordinary case on this build.
    @Test
    fun `an empty sweep claims nothing`() =
        runBlocking {
            assertEquals(
                emptyList(),
                ClaimedSweep(FixedExpired(emptyList()), PostgresHarness.database, clock)
                    .findExpired(NOW.toEpochMilliseconds(), 10),
            )
            assertEquals(0, claimRows(), "an empty sweep wrote a claim")
        }

    private fun claimRows(): Int =
        transaction(PostgresHarness.database) { SagaSweepClaimTable.selectAll().count().toInt() }

    private fun saga(id: String) =
        Petich(
            id = id,
            type = "test",
            status = PetichStatus.PENDING_SIGNATURE,
            payload = TestPayload(),
            enrichedPayload = SimpleEnrichedPayload(),
        )

    // The delegate, answering the same list every time. What is under test is the CLAIM, not petich's
    // expiry query — which `SuspendedSagaExpiryTest` covers against the same database.
    private class FixedExpired(
        private val expired: List<Petich>,
    ) : ExpiringPetichRepository,
        PetichRepository by NotUsed {
        override suspend fun findExpired(
            nowEpochMs: Long,
            limit: Int,
        ): List<Petich> = expired.take(limit)
    }

    // Every other method of the repository. It throws rather than returning a plausible answer: this
    // test calls exactly one method, and a fake that quietly answered the others would let a future
    // change to `ClaimedSweep` pass here while doing something else entirely.
    private object NotUsed : PetichRepository {
        override suspend fun findById(id: String): Petich? = error(ONLY)

        override suspend fun saveOrGet(petich: Petich): Petich = error(ONLY)

        override suspend fun update(petich: Petich): Boolean = error(ONLY)

        private const val ONLY = "ClaimedSweepTest calls only findExpired"
    }

    @Serializable
    @SerialName("test")
    private class TestPayload : PetichPayload()

    private companion object {
        val NOW: Instant = Instant.fromEpochMilliseconds(1_700_000_000_000)
    }
}
