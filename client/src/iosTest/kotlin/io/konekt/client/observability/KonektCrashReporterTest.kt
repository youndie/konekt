package io.konekt.client.observability

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// Runs on the simulator, on the Mac, and nowhere else: the Linux build compiles the Apple targets and
// SKIPS every Apple test inside a successful build (research-stack §1.7), so a green CI build says
// this code links and nothing about whether it runs.
//
//   LOCAL=1 ./gradlew :client:iosSimulatorArm64Test
//
// WHAT IT CAN AND CANNOT SETTLE. It settles the half that is konekt's: a reporter asked to start
// without the three things a crash needs to be actionable refuses, loudly, where it is configured.
// It does not settle delivery — that needs a katcher to receive and an application to crash, and
// B-27 records both as what is left.
class KonektCrashReporterTest {
    @Test
    fun `a blank app key is refused rather than reported as started`() {
        // katcher's own answer to this is a println and a return, which leaves a build that MEANT to
        // report indistinguishable from one that is reporting: both are silent, and the difference
        // shows up when somebody goes looking for a crash that was never sent.
        val failure =
            assertFailsWith<IllegalArgumentException> {
                KonektCrashReporter.start(
                    appKey = "",
                    remoteHost = "https://katcher.example",
                    release = "1.0.0",
                    environment = "test",
                )
            }

        assertTrue(
            "appKey" in failure.message.orEmpty(),
            "the refusal does not name what is missing: ${failure.message}",
        )
    }

    @Test
    fun `a blank host is refused`() {
        assertFailsWith<IllegalArgumentException> {
            KonektCrashReporter.start(
                appKey = "konekt-ios",
                remoteHost = "",
                release = "1.0.0",
                environment = "test",
            )
        }
    }

    @Test
    fun `a build that cannot name itself is refused`() {
        // `KatcherConfig.release` defaults to the string "Unspecified". A crash group that cannot say
        // which build produced it is a crash group nobody can act on, which is why B-27's acceptance
        // asks for a report NAMING the release rather than merely for a report.
        val failure =
            assertFailsWith<IllegalArgumentException> {
                KonektCrashReporter.start(
                    appKey = "konekt-ios",
                    remoteHost = "https://katcher.example",
                    release = "  ",
                    environment = "test",
                )
            }

        assertTrue(
            "release" in failure.message.orEmpty(),
            "the refusal does not name what is missing: ${failure.message}",
        )
    }
}
