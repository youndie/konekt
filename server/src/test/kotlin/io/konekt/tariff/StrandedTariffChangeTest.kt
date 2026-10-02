package io.konekt.tariff

import io.github.youndie.petich.EnrichedPayload
import io.github.youndie.petich.OutboxAwarePetichRepository
import io.github.youndie.petich.OutboxEvent
import io.github.youndie.petich.Petich
import io.github.youndie.petich.PetichEngine
import io.github.youndie.petich.PetichEngineConfig
import io.github.youndie.petich.PetichPayload
import io.github.youndie.petich.PetichStatus
import io.github.youndie.petich.ResumePayload
import io.github.youndie.petich.SimpleEnrichedPayload
import io.github.youndie.petich.SuspendedPetichSweeper
import io.github.youndie.petich.postgres.ExposedPetichRepository
import io.github.youndie.petich.postgres.OutboxEventsTable
import io.github.youndie.petich.postgres.PetichTable
import io.konekt.db.tables.SubscriberTable
import io.konekt.feature.purchase.server.data.MockPaymentGateway
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
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

// A TARIFF CHANGE WHOSE PROCESS DIED IS CARRIED TO ITS CONFIRMATION (B-131).
//
// The purchase's `StrandedFirstPassTest`, for the other saga that writes before it suspends. The
// change is recorded, the process dies at the write that parks the saga at its confirmation, and a
// second engine's sweeper re-drives `RecordTariffChange`. Its write was a plain insert under the
// unique `change_id`, so the re-run threw, petich undid the member that threw, and the change the
// subscriber asked for ended cancelled — the tariff right, the request lost.
//
// `runBlocking` and not `runTest`, for the reason TariffChangeSagaTest carries.
@OptIn(ExperimentalUuidApi::class)
class StrandedTariffChangeTest {
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

    // On the test's clock, because the stranded queue asks the store when a row was last written.
    private val repository =
        ExposedPetichRepository(PostgresHarness.database, PetichTable(json), OutboxEventsTable(), clock.asPetichClock())
    private val catalogue = StaticTariffCatalogue()
    private val changes = ExposedTariffChanges(PostgresHarness.database, clock)

    private lateinit var subscriberId: String

    private fun engine(
        repository: OutboxAwarePetichRepository = this.repository,
        changes: TariffChanges = this.changes,
    ): PetichEngine =
        PetichEngine(
            definitions = listOf(tariffPetich(catalogue, changes, json, 5.minutes)),
            repository = repository,
            config = PetichEngineConfig(requireOutbox = true),
            clock = clock.asPetichClock(),
        )

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
    fun `a change whose process died before the confirmation is carried to it`(): Unit =
        runBlocking {
            val dying = ProcessDiesAtTheNextWrite(repository)
            val recording = ChangesThatArm(changes, dying)

            assertFailsWith<ProcessDied> {
                StartTariffChangeUseCase(engine(repository = dying, changes = recording), repository, catalogue, changes, clock)(
                    StartTariffChangeUseCase.Params(subscriberId, "tr-max"),
                ).getOrThrow()
            }
            val changeId = assertNotNull(dying.died, "the process never died — nothing was tested")
            assertEquals(PetichStatus.PROCESSING, repository.findById(changeId)?.status)

            // A second process: its own engine, the real store, the server's own window.
            val sweeper =
                SuspendedPetichSweeper(
                    repository = repository,
                    engine = engine(changes = recording),
                    clock = clock.asPetichClock(),
                    stuckAfter = MockPaymentGateway.STRANDED_AFTER,
                )
            now += MockPaymentGateway.STRANDED_AFTER + 1.minutes
            assertEquals(1, sweeper.sweepStuck(), "the stranded queue did not pick the saga up")

            assertEquals(2, recording.recorded, "the change was not recorded again — nothing here was re-driven")
            assertEquals(
                OrderStatus.AWAITING_CONFIRMATION,
                OrderStatus.of(assertNotNull(repository.findById(changeId)).status),
                "a change whose process died was not carried to its confirmation",
            )
            assertEquals(changeId, changes.pendingOf(subscriberId)?.changeId, "the change is not the one waiting")

            // And it goes on to be applied, which is the point of carrying it forward.
            val confirmed =
                ConfirmTariffChangeUseCase(engine(), repository, catalogue, changes)(
                    ConfirmTariffChangeUseCase.Params(changeId, subscriberId),
                ).getOrThrow()
            assertEquals(OrderStatus.COMPLETED, confirmed.status)
            assertEquals(TariffChangeStatuses.APPLIED, assertNotNull(changes.findByChange(changeId)).status)
            assertEquals("tr-max", confirmed.requestedTariffId)
        }

    // How a process stops, as far as the engine can tell: nothing it catches.
    private class ProcessDied : Error("the process stopped before the saga was written")

    // The saga store, which dies once: at the first write after it was armed, before that write goes
    // through. `died` is the saga that was being written.
    private class ProcessDiesAtTheNextWrite(
        private val real: OutboxAwarePetichRepository,
    ) : OutboxAwarePetichRepository {
        var armed = false
        var died: String? = null

        override suspend fun findById(id: String): Petich? = real.findById(id)

        override suspend fun saveOrGet(petich: Petich): Petich = real.saveOrGet(petich)

        override suspend fun update(petich: Petich): Boolean = update(petich, emptyList())

        override suspend fun update(
            petich: Petich,
            outboxEvents: List<OutboxEvent>,
        ): Boolean {
            if (armed && died == null) {
                died = petich.id
                throw ProcessDied()
            }
            return real.update(petich, outboxEvents)
        }
    }

    // The real record, counted, which arms the store once it has landed: the next write is the one
    // that parks the saga at its confirmation.
    private class ChangesThatArm(
        private val real: TariffChanges,
        private val store: ProcessDiesAtTheNextWrite,
    ) : TariffChanges by real {
        var recorded = 0

        override suspend fun record(
            changeId: String,
            subscriberId: String,
            fromTariffId: String,
            toTariffId: String,
            effectiveAt: Instant,
        ) {
            recorded++
            real.record(changeId, subscriberId, fromTariffId, toTariffId, effectiveAt)
            store.armed = true
        }
    }
}
