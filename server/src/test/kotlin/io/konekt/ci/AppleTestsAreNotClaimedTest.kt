package io.konekt.ci

import kotlin.io.path.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// B-37's third criterion, enforced rather than promised: while no runner has a simulator runtime, no
// CI job claims to run the Apple tests.
//
// The failure it prevents is quiet in both directions. On Linux the simulator test tasks are SKIPPED
// inside a green `BUILD SUCCESSFUL` — Gradle warns, and the advice attached to that warning is a
// setting that silences the warning rather than the problem. So a job that named `iosX64Test` on an
// Ubuntu runner would go green forever while testing nothing, and the badge would say otherwise.
// ONE THING TO KNOW ABOUT EVERY GUARD OF THIS SHAPE, and it cost a confusing minute here: the file
// it reads is not a Gradle input, so a change to the workflow alone leaves `:server:test`
// UP-TO-DATE and the guard does not run. It is right in CI, where the checkout is fresh, and locally
// it needs `--rerun-tasks` to be believed. The same is true of ClockUsageTest and its siblings.
class AppleTestsAreNotClaimedTest {
    private val workflows = Path("../.github/workflows")

    private val appleTestTasks =
        listOf("iosSimulatorArm64Test", "iosX64Test", "iosArm64Test", "macosArm64Test", "macosX64Test")

    private fun files() =
        workflows
            .listDirectoryEntries()
            .filter { !it.isDirectory() && it.extension in setOf("yaml", "yml") }

    @Test
    fun `no job runs an Apple test task on a runner that cannot`() {
        val offenders =
            files()
                .flatMap { file ->
                    val text = file.readText()
                    appleTestTasks
                        .filter { task ->
                            // Named as a Gradle task rather than merely mentioned: every one of these
                            // words also appears in the prose explaining why they are absent, and a
                            // guard that fired on its own explanation would be deleted within a week.
                            Regex("""gradlew[^\n]*\b$task\b""").containsMatchIn(text)
                        }.filter { !text.contains("macos-") }
                        .map { "${file.fileName}: $it" }
                }

        assertEquals(
            emptyList(),
            offenders,
            "these name an Apple test task and no macOS runner, so the task is SKIPPED and the job " +
                "goes green having tested nothing:\n" + offenders.joinToString("\n"),
        )
    }

    @Test
    fun `the guard is looking at something`() {
        // The path is relative to a module directory, and a moved workflow would make the assertion
        // above pass by finding no files at all.
        assertTrue(files().isNotEmpty(), "no workflow files found — is the path right?")
        assertTrue(
            files().any { "gradlew" in it.readText() },
            "no workflow runs Gradle, so this guard is watching a build that does not happen here",
        )
    }
}
