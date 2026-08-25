package io.konekt.time

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
import ru.workinprogress.petich.SuspendedPetichSweeper
import ru.workinprogress.petich.isTerminal
import ru.workinprogress.petich.postgres.ExposedPetichRepository
import ru.workinprogress.petich.postgres.OutboxEventsTable
import ru.workinprogress.petich.postgres.PetichTable
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

// The acceptance of B-33, and the reason the clock is a dependency at all.
//
// A saga that suspends for a confirmation holds everything it has already done — a reserved slot, a
// claimed quota, money on hold. The deadline that ends that wait is enforced by a background sweeper
// reading a clock, so testing it against the real one means a test that sleeps for the TTL. Here the
// TTL is five minutes and the test takes milliseconds: the clock moves, nothing waits.
class SuspendedSagaExpiryTest {
    @Serializable
    @SerialName("confirmable")
    data class ConfirmablePayload(
        val note: String,
    ) : PetichPayload()

    // Records whether the rollback actually ran. The status alone would not distinguish "the engine
    // marked it terminal" from "the engine undid the work", and undoing the work is the whole point.
    class ReservingInterceptor : PetichInterceptor<ConfirmablePayload> {
        var reserved = false
        var released = false

        override val phase = PetichPhase.AUTHORIZATION

        override fun supports(payload: PetichPayload) = payload is ConfirmablePayload

        override suspend fun intercept(
            petich: Petich,
            payload: ConfirmablePayload,
        ): InterceptorResult {
            reserved = true
            // Five minutes is this step's own deadline, not the engine's: typing a one-time code and
            // approving a long-running request live on different time scales, and the step knows
            // that.
            return InterceptorResult.Suspend(requiredAction = "CONFIRM", ttl = 5.minutes)
        }

        override suspend fun compensate(
            petich: Petich,
            payload: ConfirmablePayload,
        ) {
            released = true
        }
    }

    private val json =
        Json {
            serializersModule =
                SerializersModule {
                    polymorphic(PetichPayload::class) { subclass(ConfirmablePayload::class) }
                    polymorphic(EnrichedPayload::class) { subclass(SimpleEnrichedPayload::class) }
                }
        }

    private val clock = MutableTestClock()

    private val repository =
        ExposedPetichRepository(
            db = PostgresHarness.database,
            table = PetichTable(json),
            outboxTable = OutboxEventsTable(),
        )

    private val interceptor = ReservingInterceptor()

    private val engine =
        PetichEngine(
            interceptors = listOf(interceptor),
            repository = repository,
            config = PetichEngineConfig(requireOutbox = true),
            // The same clock the sweeper reads. One notion of now for both, so a test that moves time
            // moves it for the deadline and for the sweep alike.
            clock = clock.asPetichClock(),
        )

    private val sweeper =
        SuspendedPetichSweeper(
            repository = repository,
            engineFor = { engine },
            clock = clock.asPetichClock(),
        )

    @BeforeTest
    fun clean() {
        PostgresHarness.truncateAll()
    }

    @Test
    fun `a confirmation nobody returns to is rolled back when its deadline passes`() =
        runTest {
            engine.process(
                Petich(
                    id = "confirmable-1",
                    type = "confirmable",
                    status = PetichStatus.DRAFT,
                    payload = ConfirmablePayload(note = "awaiting a code"),
                ),
            )

            val suspended = assertNotNull(repository.findById("confirmable-1"))
            assertEquals(PetichStatus.PENDING_SIGNATURE, suspended.status)
            assertTrue(interceptor.reserved, "the step that holds the resource did not run")
            assertTrue(!interceptor.released, "nothing should be released while the wait is open")
            assertNotNull(suspended.suspendedUntilEpochMs, "no deadline was stamped, so nothing would ever expire")

            // Before the deadline, a sweep must do nothing. Asserted rather than assumed: a sweeper
            // that rolled everything back regardless of time would pass the test below on its own.
            assertEquals(0, sweeper.sweep(), "a saga inside its window was swept")

            clock.advance(6.minutes)

            assertEquals(1, sweeper.sweep(), "the deadline passed and nothing was swept")

            val expired = assertNotNull(repository.findById("confirmable-1"))
            assertTrue(expired.status.isTerminal(), "an expired saga must end, it was ${expired.status}")
            assertTrue(interceptor.released, "the saga ended without undoing what it had already done")
        }
}
