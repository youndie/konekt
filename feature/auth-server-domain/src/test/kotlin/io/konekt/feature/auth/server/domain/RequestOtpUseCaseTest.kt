package io.konekt.feature.auth.server.domain

import io.konekt.domain.KonektException
import io.konekt.time.KonektClock
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

// MockK resolves here because every target of this module is the JVM. It would not in a module that
// also targets iOS, which is one of the reasons -server-domain is kotlin("jvm").
class RequestOtpUseCaseTest {
    private val challenges = mockk<OtpRepository>(relaxed = true)
    private val delivery = mockk<OtpDelivery>(relaxed = true)
    private var now = Instant.fromEpochMilliseconds(1_700_000_000_000)
    private val clock = KonektClock { now }
    private val policy = OtpPolicy()

    private val useCase =
        RequestOtpUseCase(
            challenges = challenges,
            codes = { length -> "1".repeat(length) },
            hasher = { code -> "hash:$code" },
            delivery = delivery,
            clock = clock,
            policy = policy,
        )

    @Test
    fun `an unknown number is answered exactly like a known one`() =
        runTest {
            // The property this feature has to have, and the reason it holds is structural rather
            // than careful: nothing in this use case looks a subscriber up. There is no
            // SubscriberRepository in its constructor, so there is no branch that could differ and no
            // work that could take a different amount of time. A subscriber is created on the first
            // successful VERIFY instead.
            coEvery { challenges.find(any()) } returns null

            val response = useCase("15550109999").getOrThrow()

            assertEquals(policy.codeLength, response.codeLength)
            assertEquals(policy.ttl.inWholeSeconds, response.expiresInSeconds)
            assertEquals(policy.resendAfter.inWholeSeconds, response.resendAfterSeconds)
        }

    @Test
    fun `the code is stored hashed and never as itself`() =
        runTest {
            coEvery { challenges.find(any()) } returns null
            val stored = slot<OtpChallenge>()
            coEvery { challenges.put(capture(stored)) } returns Unit

            useCase("15550109999").getOrThrow()

            assertEquals("hash:111111", stored.captured.codeHash)
            // The stored row must not be the code. Asserted as an inequality rather than trusting the
            // line above, because "the hasher was called" and "what was stored is not the code" are
            // different claims and only the second is the one that matters after a database leak.
            assertTrue(stored.captured.codeHash != "111111")
            coVerify { delivery.deliver(Msisdn.parse("15550109999"), "111111") }
        }

    @Test
    fun `asking again too soon is refused with a number of seconds`() =
        runTest {
            coEvery { challenges.find(any()) } returns
                challenge(issuedAt = now - 10.seconds, expiresAt = now + 4.minutes)

            val failure = useCase("15550109999").exceptionOrNull()

            val refusal = assertIs<KonektException.RateLimited>(failure)
            assertEquals(50, refusal.retryAfterSeconds)
            // Nothing was sent, which is the point: without this the resend button writes somebody
            // else's SMS bill.
            coVerify(exactly = 0) { delivery.deliver(any(), any()) }
        }

    @Test
    fun `a locked number stays locked through a resend`() =
        runTest {
            // Otherwise "send again" is a reset button on the attempt counter, and a six-attempt
            // budget becomes six attempts per button press.
            coEvery { challenges.find(any()) } returns
                challenge(
                    issuedAt = now - 10.minutes,
                    expiresAt = now - 5.minutes,
                    attemptsUsed = policy.maxAttempts,
                    lockedUntil = now + 10.minutes,
                )

            val failure = useCase("15550109999").exceptionOrNull()

            assertIs<KonektException.RateLimited>(failure)
            coVerify(exactly = 0) { challenges.put(any()) }
        }

    @Test
    fun `a number that is not a number is refused before anything happens`() =
        runTest {
            val failure = useCase("hello").exceptionOrNull()

            val refusal = assertIs<KonektException.Validation>(failure)
            assertEquals("msisdn", refusal.field)
            coVerify(exactly = 0) { challenges.find(any()) }
        }

    private fun challenge(
        issuedAt: Instant,
        expiresAt: Instant,
        attemptsUsed: Int = 0,
        lockedUntil: Instant? = null,
    ) = OtpChallenge(
        msisdn = Msisdn.parse("15550109999"),
        codeHash = "hash:111111",
        issuedAt = issuedAt,
        expiresAt = expiresAt,
        attemptsUsed = attemptsUsed,
        lockedUntil = lockedUntil,
    )
}
