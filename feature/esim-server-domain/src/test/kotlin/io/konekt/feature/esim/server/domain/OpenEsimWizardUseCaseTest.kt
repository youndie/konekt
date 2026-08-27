package io.konekt.feature.esim.server.domain

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

// OPENING IS NOT STARTING, and this is the whole of the difference.
//
// The install flow gained an ADDRESS so that a served tree could point at it (`B-54`) — and an
// address is fetched with a GET, which a client may repeat: a refresh, a return from the background,
// a second press. `StartEsimWizardUseCase` writes a row every time it is called, so the screen built
// on it would leave a trail of abandoned drafts and reset the subscriber's progress under them.
class OpenEsimWizardUseCaseTest {
    private val ids =
        object {
            var n = 0
        }

    private fun openerOver(sessions: FakeSessions) = OpenEsimWizardUseCase(sessions, EsimIds { "wizard-${++ids.n}" })

    @Test
    fun `opening twice is one run, not two`() =
        runTest {
            val sessions = FakeSessions()
            val open = openerOver(sessions)

            val first = open(OpenEsimWizardUseCase.Params("sub-1")).getOrThrow()
            val second = open(OpenEsimWizardUseCase.Params("sub-1")).getOrThrow()

            assertEquals(first.record.id, second.record.id, "the second arrival started a second run")
            assertEquals(1, sessions.rows.size, "a row per arrival is a draft that resets under the subscriber")
        }

    // THE POSITIVE CONTROL, and without it the test above passes on a use case that never creates
    // anything at all — which would be a screen that cannot open for a subscriber who has no run.
    @Test
    fun `a subscriber with no run gets one`() =
        runTest {
            val sessions = FakeSessions()

            openerOver(sessions)(OpenEsimWizardUseCase.Params("sub-1")).getOrThrow()

            assertEquals(1, sessions.rows.size, "opening created nothing, so the screen has no run to draw")
        }

    // Resuming is per subscriber. Sharing one run between two would be worse than starting two: the
    // draft carries what somebody chose.
    @Test
    fun `two subscribers do not share a run`() =
        runTest {
            val sessions = FakeSessions()
            val open = openerOver(sessions)

            val mine = open(OpenEsimWizardUseCase.Params("sub-1")).getOrThrow()
            val theirs = open(OpenEsimWizardUseCase.Params("sub-2")).getOrThrow()

            assertNotEquals(mine.record.id, theirs.record.id, "one run answered two subscribers")
        }

    @Test
    fun `a finished run is not resumed`() =
        runTest {
            val sessions = FakeSessions()
            val open = openerOver(sessions)

            val first = open(OpenEsimWizardUseCase.Params("sub-1")).getOrThrow()
            // Walked to the end, which is what `finished` means: the subscriber installed one and is
            // back to install another.
            sessions.save(first.record.copy(session = first.record.session.copy(isFinished = true)))

            val second = open(OpenEsimWizardUseCase.Params("sub-1")).getOrThrow()

            assertNotEquals(first.record.id, second.record.id, "a completed install blocked the next one")
        }
}
