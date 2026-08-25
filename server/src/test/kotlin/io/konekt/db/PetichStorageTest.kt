package io.konekt.db

import io.konekt.testing.PostgresHarness
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import ru.workinprogress.petich.EnrichedPayload
import ru.workinprogress.petich.InterceptorResult
import ru.workinprogress.petich.Petich
import ru.workinprogress.petich.PetichEngine
import ru.workinprogress.petich.PetichEngineConfig
import ru.workinprogress.petich.PetichInterceptor
import ru.workinprogress.petich.PetichPayload
import ru.workinprogress.petich.PetichPhase
import ru.workinprogress.petich.PetichStatus
import ru.workinprogress.petich.SimpleEnrichedPayload
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

// The acceptance of B-02, and the only way to know that V1 is right: run a real saga through the
// real engine against the real schema, then read the row back.
//
// Nothing else covers it. The schema test proves the columns match the Table definitions, and a
// column can match the definition and still be wrong for the value that goes in it — a JSONB payload
// would satisfy every type check here and come back with its keys reordered.
class PetichStorageTest {
    @Serializable
    @SerialName("probe")
    data class ProbePayload(
        val note: String,
    ) : PetichPayload()

    // The engine's forward pass, one step, doing nothing. What is under test is the storage, not the
    // business logic — and a saga with no interceptors never reaches EXECUTION, so it would prove
    // less than it looks.
    class ProbeInterceptor : PetichInterceptor<ProbePayload> {
        override val phase = PetichPhase.EXECUTION

        override fun supports(payload: PetichPayload) = payload is ProbePayload

        override suspend fun intercept(
            petich: Petich,
            payload: ProbePayload,
        ): InterceptorResult = InterceptorResult.Proceed()

        override suspend fun compensate(
            petich: Petich,
            payload: ProbePayload,
        ) = Unit
    }

    // petich publishes no SerializersModule of its own, not even for the SimpleEnrichedPayload it
    // uses as the default — so every application registers both hierarchies itself, and forgetting
    // the enriched one fails at the first saga write rather than at startup.
    private val json =
        Json {
            serializersModule =
                SerializersModule {
                    polymorphic(PetichPayload::class) { subclass(ProbePayload::class) }
                    polymorphic(EnrichedPayload::class) { subclass(SimpleEnrichedPayload::class) }
                }
        }

    // Through the factory rather than the constructor, because the constructor is not reachable from
    // a packaged file — see PetichRepositories and youndie/petich#8. This test is also what checks
    // the reflective call, since the compiler has stopped doing it.
    private val repository = PetichRepositories.exposed(PostgresHarness.database, json)

    private val engine =
        PetichEngine(
            interceptors = listOf(ProbeInterceptor()),
            repository = repository,
            // The flag petich#3 added. On here from the first saga this repository ever runs, so
            // that a repository which cannot store events can never reach production quietly: the
            // engine refuses to construct rather than dropping events at runtime.
            config = PetichEngineConfig(requireOutbox = true),
        )

    @BeforeTest
    fun clean() {
        PostgresHarness.truncateAll()
    }

    @Test
    fun `a saga runs against the migrated schema and leaves its row`() =
        runTest {
            val saga =
                Petich(
                    id = "probe-1",
                    type = "probe",
                    status = PetichStatus.DRAFT,
                    payload = ProbePayload(note = "written by B-02"),
                )

            engine.process(saga)

            val stored = repository.findById("probe-1")

            assertNotNull(stored, "the saga left no row — V1's petiches table is not what petich reads")
            assertEquals(PetichStatus.COMPLETED, stored.status)
            // The JSON column round-trip, which is the half a type check cannot see: this value went
            // through Postgres and came back, rather than being the object the test still held.
            assertEquals(ProbePayload(note = "written by B-02"), stored.payload)
            assertEquals(SimpleEnrichedPayload(), stored.enrichedPayload)
        }

    @Test
    fun `the row survives a read through a second repository instance`() =
        runTest {
            engine.process(
                Petich(
                    id = "probe-2",
                    type = "probe",
                    status = PetichStatus.DRAFT,
                    payload = ProbePayload(note = "second"),
                ),
            )

            // A fresh repository over the same database: the first assertion could pass on a cached
            // object, and this one cannot. It is the difference between "the engine returned
            // something" and "Postgres holds it".
            val fresh = PetichRepositories.exposed(PostgresHarness.database, json).findById("probe-2")

            assertEquals(ProbePayload(note = "second"), assertNotNull(fresh).payload)
        }
}
