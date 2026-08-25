package io.konekt.feature.auth.server.domain

import io.konekt.domain.Currency
import io.konekt.domain.KonektException
import io.konekt.domain.Money
import io.konekt.time.KonektClock
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class VerifyOtpUseCaseTest {
    private val challenges = mockk<OtpRepository>(relaxed = true)
    private val subscribers = mockk<SubscriberRepository>()
    private val sessions = mockk<SessionIssuer>()
    private var now = Instant.fromEpochMilliseconds(1_700_000_000_000)
    private val clock = KonektClock { now }
    private val policy = OtpPolicy()

    private val useCase =
        VerifyOtpUseCase(
            challenges = challenges,
            subscribers = subscribers,
            hasher = { code -> "hash:$code" },
            sessions = sessions,
            clock = clock,
            policy = policy,
        )

    private val msisdn = Msisdn.parse("15550109999")

    @Test
    fun `the first correct code creates the subscriber and their account together`() =
        runTest {
            coEvery { challenges.find(any()) } returns live()
            coEvery { subscribers.findByMsisdn(msisdn) } returns null
            coEvery { subscribers.createWithAccount(msisdn, any()) } returns Subscriber("sub-1", msisdn)
            coEvery { sessions.issue(any()) } returns Session("access", "refresh")

            val session = useCase(VerifyOtpUseCase.Params("15550109999", "111111")).getOrThrow()

            assertEquals(Session("access", "refresh"), session)
            // With an opening balance, in one call. A subscriber without an account is a row every
            // balance read has to defend against forever, because of one sign-up interrupted at the
            // wrong millisecond.
            coVerify { subscribers.createWithAccount(msisdn, Money.zero(Currency.DEFAULT)) }
            // Consumed before the session is issued, so two requests arriving together cannot both
            // spend one code.
            coVerify { challenges.clear(msisdn) }
        }

    @Test
    fun `a returning subscriber is not created again`() =
        runTest {
            coEvery { challenges.find(any()) } returns live()
            coEvery { subscribers.findByMsisdn(msisdn) } returns Subscriber("sub-1", msisdn)
            coEvery { sessions.issue(any()) } returns Session("access", "refresh")

            useCase(VerifyOtpUseCase.Params("15550109999", "111111")).getOrThrow()

            coVerify(exactly = 0) { subscribers.createWithAccount(any(), any()) }
        }

    @Test
    fun `a wrong code and a number nobody asked about answer the same thing`() =
        runTest {
            coEvery { challenges.find(any()) } returns live()
            val wrongCode = useCase(VerifyOtpUseCase.Params("15550109999", "222222")).exceptionOrNull()

            coEvery { challenges.find(any()) } returns null
            val noChallenge = useCase(VerifyOtpUseCase.Params("15550109999", "111111")).exceptionOrNull()

            // Same type, same field, same words. Any difference here is a question about the number
            // that anybody may ask as often as they like.
            val a = assertIs<KonektException.Validation>(wrongCode)
            val b = assertIs<KonektException.Validation>(noChallenge)
            assertEquals(a.code, b.code)
            assertEquals(a.field, b.field)
            assertEquals(a.message, b.message)
        }

    @Test
    fun `the last of six wrong codes locks the number for the stated interval`() =
        runTest {
            coEvery { challenges.find(any()) } returns live(attemptsUsed = policy.maxAttempts - 1)

            val failure = useCase(VerifyOtpUseCase.Params("15550109999", "222222")).exceptionOrNull()

            val refusal = assertIs<KonektException.RateLimited>(failure)
            assertEquals(policy.lockout.inWholeSeconds, refusal.retryAfterSeconds)
            coVerify { challenges.recordFailedAttempt(msisdn, now + policy.lockout) }
        }

    @Test
    fun `a wrong code below the limit does not lock`() =
        runTest {
            coEvery { challenges.find(any()) } returns live(attemptsUsed = 0)

            val failure = useCase(VerifyOtpUseCase.Params("15550109999", "222222")).exceptionOrNull()

            assertIs<KonektException.Validation>(failure)
            coVerify { challenges.recordFailedAttempt(msisdn, null) }
        }

    @Test
    fun `an expired code says so rather than pretending to be wrong`() =
        runTest {
            coEvery { challenges.find(any()) } returns live(expiresAt = now - 1.minutes)

            val failure = useCase(VerifyOtpUseCase.Params("15550109999", "111111")).exceptionOrNull()

            // Distinguished on purpose: it tells the subscriber what to DO. It reveals nothing,
            // because an expired challenge exists for any number that requested one, known or not.
            val refusal = assertIs<KonektException.Validation>(failure)
            assertEquals("code", refusal.field)
            coVerify(exactly = 0) { challenges.recordFailedAttempt(any(), any()) }
        }

    @Test
    fun `a locked number is refused before the code is even looked at`() =
        runTest {
            coEvery { challenges.find(any()) } returns live(lockedUntil = now + 10.minutes)

            // The right code, and it still does not work. A lockout that a correct guess could end
            // would be a lockout an attacker ends by guessing correctly.
            val failure = useCase(VerifyOtpUseCase.Params("15550109999", "111111")).exceptionOrNull()

            assertIs<KonektException.RateLimited>(failure)
            coVerify(exactly = 0) { challenges.clear(any()) }
        }

    private fun live(
        attemptsUsed: Int = 0,
        expiresAt: Instant = now + 5.minutes,
        lockedUntil: Instant? = null,
    ) = OtpChallenge(
        msisdn = msisdn,
        codeHash = "hash:111111",
        issuedAt = now,
        expiresAt = expiresAt,
        attemptsUsed = attemptsUsed,
        lockedUntil = lockedUntil,
    )
}
