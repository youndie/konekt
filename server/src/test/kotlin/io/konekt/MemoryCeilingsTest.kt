package io.konekt

import kotlin.io.path.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// THE JVM'S CEILINGS, IN THE TWO FILES THAT SET THEM — the chart for a cluster, the compose file for
// the stand. The same shape as `ComposeStandTest`'s pairing of the broker's retention, and for the
// same reason: a number set in one file and forgotten in the other is a defect this repository has
// already had twice, and both times the half that was wrong was the one nobody ran.
//
// What is being protected here is not tidiness. The stand is where a measurement is taken and where
// `make e2e` proves the product still works; a stand whose JVM has a different heap, a different
// collector or a different stack size from the deployment's is a stand that cannot find what the
// deployment will — and `-Xss256k` in particular is the kind of ceiling whose failure is a
// `StackOverflowError` on one path in one screen.
//
// THE VALUES ARE COMPARED, NOT JUST THE FLAG NAMES. A stand running `-Xmx512M` beside a chart
// running `-Xmx64M` carries every flag this test could look for by name.
class MemoryCeilingsTest {
    private val chart = Path("../charts/konekt/values.yaml").readText()
    private val compose = Path("../deploy/compose.yaml").readText()

    // The chart's block scalar: `jvmOptions: >-` followed by more-indented lines. Folded into one
    // line the way helm folds it, so the comparison is against the string a pod actually receives.
    private val chartOptions: String =
        chart
            .lines()
            .dropWhile { !it.trimStart().startsWith("jvmOptions:") }
            .drop(1)
            .takeWhile { it.isBlank() || it.startsWith("    ") }
            .filter { it.isNotBlank() }
            .joinToString(" ") { it.trim() }

    // Every service in the stand that runs this image, because the migration is the same JVM with a
    // switch and is the one that runs FIRST — a ceiling it cannot live with fails the deploy before
    // the server has a chance to.
    private fun standOptions(): List<Pair<String, String>> =
        // Not a raw string: a raw one cannot escape the `$` of `${SERVER_JVM_OPTIONS…}`, which
        // Kotlin would read as a template expression and refuse to compile.
        Regex("JAVA_TOOL_OPTIONS: \\\$\\{SERVER_JVM_OPTIONS:-(.*?)}")
            .findAll(compose)
            .map { "deploy/compose.yaml" to it.groupValues[1].trim() }
            .toList()

    private fun ceilings(options: String): Map<String, String> =
        Regex("""(-Xmx|-Xms|-Xss|-XX:[A-Za-z]+=|-XX:[+-][A-Za-z]+)([0-9]+[kKmMgG]?)?""")
            .findAll(options)
            .associate { it.groupValues[1].trimEnd('=') to it.groupValues[2] }

    @Test
    fun `the chart bounds the heap explicitly`() {
        // WITHOUT THIS THE HEAP IS A SHARE OF WHATEVER THE LIMIT HAPPENS TO BE, and which share is
        // not the quarter everybody quotes: at this chart's 256Mi the ergonomics size it off
        // `MinRAMPercentage` and take HALF — measured at 125 MiB committed, more than the unbounded
        // JVM was using at a gigabyte. A heap that follows the limit also grows the moment somebody
        // gives the pod room for something else.
        assertTrue(
            chartOptions.contains("-Xmx"),
            "charts/konekt/values.yaml sets server.jvmOptions without -Xmx, so the heap is a share " +
                "of the container limit and moves with it:\n$chartOptions",
        )
        assertTrue(
            chartOptions.contains("-XX:+UseSerialGC"),
            "the collector is not named, so it is whatever ergonomics picks for the cpu count of " +
                "the day:\n$chartOptions",
        )
    }

    @Test
    fun `the stand runs the ceilings the chart deploys`() {
        val expected = ceilings(chartOptions)
        val stands = standOptions()

        assertTrue(
            stands.size >= 2,
            "expected the stand's server AND its migration to carry JAVA_TOOL_OPTIONS; found ${stands.size}. " +
                "The migration is the same image with a switch and runs first, so a ceiling it cannot " +
                "live with fails the deploy before the server starts",
        )

        for ((file, options) in stands) {
            assertEquals(
                expected,
                ceilings(options),
                "$file and charts/konekt/values.yaml disagree about what bounds the JVM.\n" +
                    "  chart: $chartOptions\n  stand: $options",
            )
        }
    }
}
