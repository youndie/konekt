package io.konekt.tariff

import io.konekt.db.tables.SubscriberTable
import io.konekt.feature.purchase.server.domain.OrderStatus
import io.konekt.testing.PostgresHarness
import io.konekt.time.KonektClock
import io.konekt.time.asPetichClock
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import ru.workinprogress.petich.EnrichedPayload
import ru.workinprogress.petich.ExpiringPetichRepository
import ru.workinprogress.petich.PetichEngine
import ru.workinprogress.petich.PetichEngineConfig
import ru.workinprogress.petich.PetichPayload
import ru.workinprogress.petich.ResumePayload
import ru.workinprogress.petich.SimpleEnrichedPayload
import ru.workinprogress.petich.SuspendedPetichSweeper
import ru.workinprogress.petich.postgres.ExposedPetichRepository
import ru.workinprogress.petich.postgres.OutboxEventsTable
import ru.workinprogress.petich.postgres.PetichTable
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

// THE SECOND SAGA WITH A CONFIRMATION, against a real Postgres.
//
// `runBlocking` and not `runTest`, for the reason every saga test here carries: the engine wraps each
// step in `withTimeout`, and a virtual clock cancels the first real database call inside one — the
// saga then compensates for no reason, and petich swallows the cancellation into the compensation so
// nothing is logged.
@OptIn(ExperimentalUuidApi::class)
class TariffChangeSagaTest {
    // A movable clock, because the whole point of the second acceptance criterion is what happens
    // when a deadline passes. The sweeper reads the same one through `asPetichClock`.
    private var now = Instant.fromEpochMilliseconds(1_700_000_000_000)
    private val clock = KonektClock { now }

    private val json =
        Json {
            serializersModule =
                SerializersModule {
                    polymorphic(PetichPayload::class) { subclass(TariffChangePayload::class) }
                    polymorphic(EnrichedPayload::class) { subclass(SimpleEnrichedPayload::class) }
                    polymorphic(ResumePayload::class) { subclass(TariffConfirmation::class) }
                }
        }

    private val repository = ExposedPetichRepository(PostgresHarness.database, PetichTable(json), OutboxEventsTable())
    private val catalogue = StaticTariffCatalogue()
    private val changes = ExposedTariffChanges(PostgresHarness.database, clock)

    private val engine =
        PetichEngine(
            interceptors = tariffInterceptors(catalogue, changes, json, 5.minutes),
            repository = repository,
            config = PetichEngineConfig(requireOutbox = true),
            clock = clock.asPetichClock(),
        )

    private val start = StartTariffChangeUseCase(engine, repository, catalogue, changes, clock)
    private val confirm = ConfirmTariffChangeUseCase(engine, repository, catalogue, changes)

    private lateinit var subscriberId: String

    @BeforeTest
    fun seed() {
        PostgresHarness.truncateAll()
        val id = Uuid.random().toString()
        subscriberId = id
        transaction(PostgresHarness.database) {
            SubscriberTable.insert {
                it[SubscriberTable.id] = id
                it[msisdn] = "1555010${(1000..9999).random()}"
                it[createdAt] = 0
            }
        }
    }

    @Test
    fun `a confirmed change shows the new tariff and the old one still current`(): Unit =
        runBlocking {
            val asked = start(StartTariffChangeUseCase.Params(subscriberId, "tr-max")).getOrThrow()

            assertEquals(OrderStatus.AWAITING_CONFIRMATION, asked.status)
            assertEquals(ACTION_CONFIRM_TARIFF, asked.requiredAction)

            val settled = confirm(ConfirmTariffChangeUseCase.Params(asked.changeId, subscriberId)).getOrThrow()

            assertEquals(OrderStatus.COMPLETED, settled.status)
            assertEquals("tr-max", settled.requestedTariffId)

            // B-21's FIRST acceptance criterion, and the part a naive implementation gets wrong: the
            // change is confirmed and the subscriber is STILL on the old tariff, because it takes
            // effect on a boundary. Both are true at once and the screen has to say both.
            assertEquals(catalogue.default.id, settled.currentTariffId)
            assertTrue(settled.effectiveAt.toEpochMilliseconds() > now.toEpochMilliseconds())
        }

    @Test
    fun `an unconfirmed change past its deadline leaves the current tariff untouched`(): Unit =
        runBlocking {
            val asked = start(StartTariffChangeUseCase.Params(subscriberId, "tr-max")).getOrThrow()
            assertNotNull(changes.pendingOf(subscriberId), "the change was not recorded as pending")

            // Past the step's own TTL. The sweeper rolls it back — the same machinery the purchase
            // saga uses, which is the reason this feature reuses it rather than growing a deadline of
            // its own.
            now = Instant.fromEpochMilliseconds(now.toEpochMilliseconds() + 6.minutes.inWholeMilliseconds)

            SuspendedPetichSweeper(
                repository = repository as ExpiringPetichRepository,
                engineFor = { engine },
                clock = clock.asPetichClock(),
            ).sweep()

            // THE ACCEPTANCE CRITERION. Not "the saga failed" — what matters is that the tariff did
            // not move, and that a cancelled change cannot become the answer to "what are they on".
            assertNull(changes.currentTariffId(subscriberId), "a swept change became the current tariff")
            assertNull(changes.pendingOf(subscriberId), "the change is still pending after the sweep")
            assertEquals(
                TariffChangeStatuses.CANCELLED,
                assertNotNull(changes.findByChange(asked.changeId)).status,
            )
        }

    @Test
    fun `one pending change at a time`(): Unit =
        runBlocking {
            start(StartTariffChangeUseCase.Params(subscriberId, "tr-max")).getOrThrow()

            // Two would race for the same boundary and the later would win by accident of ordering —
            // and a subscriber who asked twice would have no way to know which they got.
            val second = start(StartTariffChangeUseCase.Params(subscriberId, "tr-standard")).getOrThrow()

            assertEquals(OrderStatus.REJECTED, second.status)
        }

    @Test
    fun `the tariff you are already on is not a change`(): Unit =
        runBlocking {
            val same = start(StartTariffChangeUseCase.Params(subscriberId, catalogue.default.id)).getOrThrow()

            assertEquals(OrderStatus.REJECTED, same.status)
        }
}
