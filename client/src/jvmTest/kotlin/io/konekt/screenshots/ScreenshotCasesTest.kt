package io.konekt.screenshots

import io.konekt.components.CounterStates
import ru.workinprogress.viddik.generated.GeneratedViddikRegistry
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.absolute
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

// THE HALF OF THE CASE-COUNT GUARD THAT CANNOT BE SKIPPED.
//
// A generated screenshot suite has one failure mode that looks exactly like success: it executes
// nothing. viddik generates `GeneratedViddikTests`, a JUnit 5 `@TestFactory` producing one DYNAMIC
// test per fixture — so a module wired for JUnit 4 compiles it, KSP writes it to disk, and the run is
// green with zero cases in it. That has happened in this account's projects before; it is why the
// build file pins `useJUnitPlatform()` and reads the case count back out of the task's own XML.
//
// This file is the other half, and it holds where the build-file check does not: a `doLast` is
// skipped when the task is UP-TO-DATE, and this is an ordinary test in the ordinary suite. It is also
// stricter in the one way that matters most — **it names the fixtures.** A count alone is satisfied
// by any eight cases; the list below is satisfied only by these eight.
//
// The strongest thing here is not an assertion at all: `GeneratedViddikRegistry` is GENERATED, and
// KSP writes nothing when it finds no annotated function. If the processor stops running, this file
// fails to COMPILE, which is a louder failure than any assertion could be.
class ScreenshotCasesTest {
    // Every case viddik should have generated, spelled as it names itself: "$group - $name". The two
    // `Dark` entries are not fixtures of ours — `darkVariant = true` makes viddik emit a second case
    // per subject, and they are listed because a variant silently dropped by a version bump would
    // otherwise take the whole dark half of the canvas with it.
    private val expectedCases =
        setOf(
            "Counter - Normal",
            "Counter - Low",
            "Counter - Exhausted",
            "Counter - Unknown state",
            "Brand - A",
            "Brand - A Dark",
            "Brand - B",
            "Brand - B Dark",
            // The only frame here photographing a tree this test did not assemble: it is decoded from
            // a response recorded off the running stand. Every other case is values written in a
            // fixture, which cannot fail when the SERVER stops sending what it draws.
            "Screen - Recorded home",
            "Screen - Recorded home Dark",
            // THE GALLERY: every screen this build serves, each one a recording like the frame above
            // and for the same reason. They exist to be held against the design canvas, which is a
            // person's job — what a machine can do is notice when one of them stops looking like
            // itself, and that is what a golden per screen buys.
            "Gallery - Login",
            "Gallery - Login Dark",
            "Gallery - Login code",
            "Gallery - Login code Dark",
            "Gallery - Home",
            "Gallery - Home Dark",
            "Gallery - Plans",
            "Gallery - Plans Dark",
            "Gallery - Plan detail",
            "Gallery - Plan detail Dark",
            "Gallery - Orders",
            "Gallery - Orders Dark",
            "Gallery - Profile",
            "Gallery - Profile Dark",
            "Gallery - Purchase result",
            "Gallery - Purchase result Dark",
            // The two flows that were reachable from nowhere until B-40's screen and B-54's door.
            "Gallery - Top up",
            "Gallery - Top up Dark",
            // The same seven on brand B. Section 08's claim — same markup, ink palette, tighter
            // radii — held at the size a screen actually is rather than on a pair of cards.
            "Gallery - B Login",
            "Gallery - B Login Dark",
            "Gallery - B Login code",
            "Gallery - B Login code Dark",
            "Gallery - B Home",
            "Gallery - B Home Dark",
            "Gallery - B Plans",
            "Gallery - B Plans Dark",
            "Gallery - B Plan detail",
            "Gallery - B Plan detail Dark",
            "Gallery - B Orders",
            "Gallery - B Orders Dark",
            "Gallery - B Profile",
            "Gallery - B Profile Dark",
            "Gallery - B Purchase result",
            "Gallery - B Purchase result Dark",
            // THE APPLICATION rather than the screen, and the reason this group exists at all is in
            // `AppFrameScreenshots`: everything above is a tree handed to `RenderNode`, so the
            // margin, the ground, the scroll and the bar at the bottom of the window are drawn by
            // nothing and photographed by nothing. Six frame-level defects were found by a person
            // looking at the running application while this suite stayed green.
            "AppFrame - App home",
            "AppFrame - App home Dark",
            "AppFrame - App plans",
            "AppFrame - App plans Dark",
            "AppFrame - App orders",
            "AppFrame - App orders Dark",
            "AppFrame - App profile",
            "AppFrame - App profile Dark",
            "AppFrame - App plan detail",
            "AppFrame - App plan detail Dark",
            "AppFrame - App login",
            "AppFrame - App login Dark",
            "AppFrame - App esim install",
            "AppFrame - App esim install Dark",
            "AppFrame - App confirm",
            "AppFrame - App confirm Dark",
            // The state a first-time subscriber reaches by pressing the first thing they see, and the
            // one whose copy was rewritable by a green suite for as long as nothing photographed it.
            "AppFrame - App purchase refused",
            "AppFrame - App purchase refused Dark",
            // Between paying and scanning, on the two screens that both got it wrong the same way.
            "AppFrame - App home uninstalled",
            "AppFrame - App home uninstalled Dark",
            "AppFrame - App profile uninstalled",
            "AppFrame - App profile uninstalled Dark",
            // The two steps with a size question on them, at the size the product is for.
            "AppFrame - App esim activate",
            "AppFrame - App esim activate Dark",
            "AppFrame - App esim done",
            "AppFrame - App esim done Dark",
            // The one frame in the file that is not phone-shaped, and the only place the code's
            // ceiling is visible. No dark variant: what it is photographing is a size.
            "AppFrame - Esim activate wide",
        )

    @Test
    fun `the generated registry holds exactly the cases this item photographs`() {
        val actual = GeneratedViddikRegistry.components.map { "${it.group} - ${it.name}" }.toSet()

        assertTrue(actual.isNotEmpty(), "KSP generated no screenshot cases at all")
        assertEquals(
            expectedCases,
            actual,
            "the generated cases and the list this test names disagree — a fixture was added, renamed " +
                "or dropped without anybody deciding it should be photographed",
        )
    }

    @Test
    fun `every case has a committed golden and every golden belongs to a case`() {
        val expectedFiles = GeneratedViddikRegistry.components.map { fileNameFor(it.group, it.name) }.toSet()
        val onDisk = snapshotsDirectory.listDirectoryEntries("*.png").map { it.name }.toSet()

        // Both directions on purpose. A case with no golden fails the comparison anyway, with a
        // message about recording; a GOLDEN WITH NO CASE fails nothing at all — it is simply never
        // consulted again, so a renamed fixture leaves a photograph of the old one in the repository
        // looking exactly as authoritative as the rest.
        assertEquals(
            expectedFiles,
            onDisk,
            "the goldens on disk and the generated cases disagree. Missing files need " +
                "`LOCAL=1 ./gradlew :client:viddikRecord`; extra ones are photographs of fixtures that " +
                "no longer exist and should be deleted",
        )
    }

    @Test
    fun `the unknown counter state is a word this build genuinely does not know`() {
        // The vacuity guard for `Counter - Unknown state`. That frame's whole claim is that a word
        // this build has never heard of draws the ORDINARY card — and the day somebody adds
        // `grace_period` to `CounterStates`, the fixture keeps passing while testing the opposite of
        // what it says. A negative fixture needs something watching that it stays negative.
        val known = setOf(CounterStates.NORMAL, CounterStates.LOW, CounterStates.EXHAUSTED)

        assertTrue(
            UNKNOWN_COUNTER_STATE !in known,
            "$UNKNOWN_COUNTER_STATE became a known counter state, so the degradation frame now " +
                "photographs a state this build recognises and proves nothing",
        )
    }

    private companion object {
        // Resolved by walking up to the repository root, the same way `BrandKits` does and for the
        // same reason: `:client` pins no `workingDir`, so a Gradle default that moved would turn
        // every listing below into a pass over an empty directory.
        val snapshotsDirectory: Path by lazy {
            var candidate = Path("").absolute()
            while (!candidate.resolve("settings.gradle.kts").exists()) {
                candidate = candidate.parent ?: fail("no settings.gradle.kts above ${Path("").absolute()}")
            }

            candidate.resolve("client/src/jvmTest/snapshots").also {
                if (!it.isDirectory()) fail("the goldens are not at $it")
            }
        }

        // viddik's own naming, reproduced rather than guessed: `"${group}_${name}"` with everything
        // outside `[A-Za-z0-9_.-]` replaced by an underscore, plus `.png`. A copy of a rule is a
        // liability, so the test above compares BOTH directions — if this ever stops matching what
        // viddik writes, the mismatch is a failure rather than a silently empty check.
        fun fileNameFor(
            group: String,
            name: String,
        ): String = "${group}_$name".replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".png"
    }
}
