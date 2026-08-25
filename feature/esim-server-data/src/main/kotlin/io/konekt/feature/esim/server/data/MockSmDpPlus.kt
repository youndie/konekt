package io.konekt.feature.esim.server.data

import io.konekt.feature.esim.server.domain.EsimRefusals
import io.konekt.feature.esim.server.domain.IssuedProfile
import io.konekt.feature.esim.server.domain.SmDpPlus
import org.slf4j.LoggerFactory
import kotlin.random.Random

// The subscription manager this system does not have — GSMA's SM-DP+, which issues eSIM profiles.
//
// It exists to be able to REFUSE, and to refuse for one reason in particular. The canvas names the
// eight-profile limit as "the failure this flow actually hits in the field", and a mock that only
// ever succeeds cannot draw that frame at all — which would leave the one screen the flow was
// designed around unreachable and therefore untested.
class MockSmDpPlus(
    private val random: Random = Random.Default,
    private val slotLimit: Int = DEVICE_PROFILE_LIMIT,
    private val serverAddress: String = SERVER_ADDRESS,
) : SmDpPlus {
    private val logger = LoggerFactory.getLogger("io.konekt.mocks.smdp")

    override suspend fun capacityFor(profilesHeld: Int): SmDpPlus.Capacity =
        if (profilesHeld < slotLimit) {
            SmDpPlus.Capacity.Available
        } else {
            SmDpPlus.Capacity.Refused(
                code = EsimRefusals.SLOT_LIMIT,
                // THE COPY IS WRITTEN HERE AND THE CANVAS DID NOT SUPPLY IT. The canvas names the
                // failure and draws the frame; the sentence in it is not quoted anywhere we can
                // read, so this is ours and is recorded as ours in the design document.
                //
                // Two things it has to do, and a shorter sentence does neither: state the limit as a
                // fact about the device rather than a fault, and say what to do next. "Could not add
                // eSIM" is what sends somebody to support.
                text =
                    "This device already holds $slotLimit eSIM profiles, which is as many as it can store. " +
                        "Remove one you no longer use, then start again.",
            )
        }

    override suspend fun issue(subscriberId: String): IssuedProfile {
        val matchingId = matchingId()
        logger.info("DEV ONLY — issuing a mock profile for subscriber {}", subscriberId)

        return IssuedProfile(
            iccid = iccid(),
            // The activation code an LPA reads: version, the server that holds the profile, and the
            // matching id that names it. The QR is this string and nothing else, which is why the
            // server never has to draw one.
            activationCode = "LPA:1\$$serverAddress\$$matchingId",
        )
    }

    // Nineteen digits with a Luhn check digit, because that is what an ICCID is.
    //
    // A random nineteen-digit number would look identical on a screen and would fail the first thing
    // any real tool does with it. The point of a mock is that everything downstream of it cannot tell
    // — and a check digit is exactly the sort of detail whose absence is discovered late.
    private fun iccid(): String {
        // 89 is the telecom major industry identifier; the rest of the prefix is this operator's.
        val body =
            ICCID_PREFIX +
                (1..(ICCID_LENGTH - 1 - ICCID_PREFIX.length)).joinToString("") { random.nextInt(10).toString() }
        return body + luhnCheckDigit(body)
    }

    private fun matchingId(): String = (1..8).joinToString("") { HEX[random.nextInt(HEX.length)].toString() }

    private fun luhnCheckDigit(digits: String): Int {
        // Doubling runs from the right, and the check digit's own position is what decides which of
        // the two alternating sets is doubled. Counting from the right of the body — the position the
        // check digit will occupy is index 0 — keeps that independent of the body's length.
        val sum =
            digits
                .reversed()
                .mapIndexed { index, char ->
                    val digit = char.digitToInt()
                    // The body's rightmost digit sits immediately left of the check digit, so it is the
                    // one that gets doubled.
                    if (index % 2 == 0) (digit * 2).let { if (it > 9) it - 9 else it } else digit
                }.sum()

        return (10 - sum % 10) % 10
    }

    companion object {
        // The eight the canvas names. A device's eUICC holds a fixed number of profiles, and this is
        // the number that flow meets in the field.
        const val DEVICE_PROFILE_LIMIT = 8

        // The activation code the design canvas is drawn with, so a frame photographed from one and a
        // frame from the running application carry the same shape of string.
        const val SERVER_ADDRESS = "rsp.konekt.io"

        const val ICCID_LENGTH = 19

        private const val ICCID_PREFIX = "8944"

        private const val HEX = "0123456789ABCDEF"
    }
}
