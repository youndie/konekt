package io.konekt.feature.purchase.server.data

import io.konekt.domain.Money
import io.konekt.feature.purchase.server.domain.PaymentGateway
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// The payment provider this system does not have.
//
// Three modes, chosen by configuration, and the second one is the reason the class exists: the
// canvas draws four purchase frames and one of them is a decline, which a provider that always
// succeeds cannot reach. A random failure rate would also reach it — and would reach it during the
// demonstration and not during the rehearsal, which is why the mode is a switch and not a die.
class MockPaymentGateway(
    private val mode: Mode = Mode.APPROVE,
    private val delay: Duration = Duration.ZERO,
    // THE PROVIDER'S OWN WORDS, and a code with them. The canvas writes the rollback as
    // "Reference 8f21-4c90 · declined by issuer (51)", and the code is the half that makes the
    // sentence actionable: a subscriber quoting one to their bank gets an answer, and a subscriber
    // told "the provider declined the operation" gets a shrug.
    //
    // 51 is "insufficient funds" in ISO 8583, which is the most ordinary decline there is. Invented
    // here in the sense that every response from this gateway is — there is no provider — and shaped
    // like a real one so the screen it lands on is shaped like a real one.
    private val declineReason: String = "Declined by the issuer (51).",
) : PaymentGateway {
    private val logger = LoggerFactory.getLogger("io.konekt.mocks.payment")

    enum class Mode {
        APPROVE,
        DECLINE,
    }

    override suspend fun settle(
        orderId: String,
        amount: Money,
    ): PaymentGateway.Settlement {
        if (delay > Duration.ZERO) {
            // A REAL suspension inside somebody else's withTimeout. The engine bounds an EXECUTION
            // step, so a delay above that bound demonstrates a phase timeout rather than a slow
            // provider — see EXECUTION_PHASE_TIMEOUT for the number and why the canvas's copy forced
            // it up.
            delay(delay)
        }

        return when (mode) {
            Mode.APPROVE -> {
                PaymentGateway.Settlement.Approved
            }

            Mode.DECLINE -> {
                logger.info("DEV ONLY — declining {} for order {} because the mock is set to decline", amount, orderId)
                PaymentGateway.Settlement.Declined(declineReason)
            }
        }
    }

    companion object {
        // The canvas tells the subscriber "this usually takes under 15 seconds", and petich's default
        // EXECUTION timeout is 10. So the copy on the screen describes a provider the engine would
        // cancel — which is the kind of contradiction that only shows up when both halves are built.
        //
        // Raised to 30 seconds rather than the copy lowered: fifteen seconds is what a real card
        // network can take, and a timeout that fires before the provider has answered turns a slow
        // approval into a rollback the subscriber never asked for.
        val EXECUTION_PHASE_TIMEOUT: Duration = 30.seconds
    }
}
