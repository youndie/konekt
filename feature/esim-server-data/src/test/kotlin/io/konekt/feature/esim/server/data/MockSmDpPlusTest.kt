package io.konekt.feature.esim.server.data

import io.konekt.feature.esim.server.domain.EsimRefusals
import io.konekt.feature.esim.server.domain.SmDpPlus
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// What the mock hands back has to be indistinguishable from the real thing to everything downstream,
// which for an ICCID means a valid check digit. A random nineteen-digit number looks identical on a
// screen and fails the first tool that validates one — and that is discovered late, by somebody else.
class MockSmDpPlusTest {
    private val smDpPlus = MockSmDpPlus(random = Random(20260825))

    @Test
    fun `the iccid is nineteen digits and passes Luhn`() =
        runBlocking {
            repeat(50) {
                val iccid = smDpPlus.issue("sub-1").iccid

                assertEquals(MockSmDpPlus.ICCID_LENGTH, iccid.length, "wrong length: $iccid")
                assertTrue(iccid.all { c -> c.isDigit() }, "not all digits: $iccid")
                assertTrue(iccid.startsWith("89"), "an ICCID starts with the telecom identifier: $iccid")
                assertTrue(luhnValid(iccid), "check digit is wrong: $iccid")
            }
        }

    @Test
    fun `the activation code is an LPA string naming this operator's server`() =
        runBlocking {
            val code = smDpPlus.issue("sub-1").activationCode
            val parts = code.split('$')

            // Three parts and not two: version, server, matching id. A code missing the last one
            // parses and installs nothing.
            assertEquals(3, parts.size, "not an LPA activation code: $code")
            assertEquals("LPA:1", parts[0])
            assertEquals(MockSmDpPlus.SERVER_ADDRESS, parts[1])
            assertEquals(8, parts[2].length)
            assertTrue(parts[2].all { it in "0123456789ABCDEF" }, "matching id is not hex: ${parts[2]}")
        }

    @Test
    fun `two profiles are not the same profile`() =
        runBlocking {
            val codes = (1..20).map { smDpPlus.issue("sub-1") }

            assertEquals(20, codes.map { it.iccid }.toSet().size, "an ICCID repeated")
            assertEquals(20, codes.map { it.activationCode }.toSet().size, "an activation code repeated")
        }

    @Test
    fun `the limit refuses at the limit and not before it`() =
        runBlocking {
            // Both sides of the boundary, because a comparison written the wrong way round refuses
            // the eighth profile to somebody holding seven — and that refusal reads exactly like a
            // correct one.
            assertEquals(SmDpPlus.Capacity.Available, smDpPlus.capacityFor(MockSmDpPlus.DEVICE_PROFILE_LIMIT - 1))

            val refused = smDpPlus.capacityFor(MockSmDpPlus.DEVICE_PROFILE_LIMIT)
            assertTrue(refused is SmDpPlus.Capacity.Refused, "the limit did not refuse")
            assertEquals(EsimRefusals.SLOT_LIMIT, refused.code)
        }

    @Test
    fun `the refusal says what happened and what to do about it`() =
        runBlocking {
            val refused = smDpPlus.capacityFor(MockSmDpPlus.DEVICE_PROFILE_LIMIT) as SmDpPlus.Capacity.Refused

            // The copy review, in the same place as the code. The canvas names this failure and does
            // not supply the sentence, so this is the assertion that keeps it from being "simplified"
            // into "could not add eSIM" — which is what sends somebody to support.
            assertTrue("8" in refused.text, "the limit is not stated: ${refused.text}")
            assertTrue("Remove one" in refused.text, "nothing to do next: ${refused.text}")
        }

    // Written out rather than reusing the production check digit, on purpose: a test that calls the
    // code it is checking agrees with it by construction, including when both are wrong.
    private fun luhnValid(number: String): Boolean {
        val sum =
            number
                .reversed()
                .mapIndexed { index, char ->
                    val digit = char.digitToInt()
                    if (index % 2 == 1) (digit * 2).let { if (it > 9) it - 9 else it } else digit
                }.sum()

        return sum % 10 == 0
    }
}
