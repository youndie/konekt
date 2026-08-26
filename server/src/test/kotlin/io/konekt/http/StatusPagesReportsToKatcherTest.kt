package io.konekt.http

import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertTrue

// THE SERVER'S CRASH REPORTER COULD NOT RECEIVE ANYTHING, AND NOTHING SAID SO.
//
// `Katcher.start` installs an uncaught-exception handler. A route's exception never reaches one:
// `StatusPages` catches it and answers 500, which is the entire purpose of that plugin. So katcher was
// correctly configured, correctly started, its ingest address answered, and no failure a route
// produced was ever going to be sent to it. The stand assertion for B-26 checked that the address was
// not a 404 — which is true of an address nobody posts to.
//
// `Katcher.catch` is the manual half of the same library and the handler that swallows the exception
// is the only place that can call it. This guard reads the source for that call, in the same idiom as
// `RunCatchingUsageTest` and `CallRespondUsageTest`: it is a claim about a CALL SITE existing, which
// is exactly what was missing, and deliberately not a claim about delivery — that is the stand's job
// and it is not yet provable there (see B-26).
class StatusPagesReportsToKatcherTest {
    private val source: Path =
        Path.of("..", "shared", "server-common", "src", "main", "kotlin", "io", "konekt", "http", "StatusPages.kt")

    @Test
    fun `the unhandled-exception branch reports the failure`() {
        val text = source.readText()

        // The branch, and the call inside it. Asserted together rather than "the file mentions
        // Katcher": a call sitting beside the handler instead of inside it would satisfy the weaker
        // check and report nothing, which is the state this test exists to end.
        val branch =
            text.substringAfter("exception<Throwable>", missingDelimiterValue = "")
        assertTrue(branch.isNotBlank(), "StatusPages no longer has a catch-all branch: nothing would be reported")
        assertTrue(
            "Katcher.catch(" in branch,
            "the catch-all branch does not report to katcher, so a route failure reaches no collector",
        )

        // AND THE DOMAIN'S REFUSALS ARE NOT REPORTED. A 404 for an order that is not yours is an
        // ANSWER, not a defect; reporting one would fill the crash groups with the product working
        // correctly, which is how an operator learns to ignore them.
        val refusals = text.substringBefore("exception<Throwable>")
        assertTrue(
            "Katcher.catch(" !in refusals,
            "a domain refusal is being reported as a crash — 4xx is an answer, not a defect",
        )
    }
}
