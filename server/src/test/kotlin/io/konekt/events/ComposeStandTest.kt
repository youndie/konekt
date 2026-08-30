package io.konekt.events

import kotlin.io.path.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// A guard on the compose file, because one line of YAML is the whole of the broker's security model.
//
// booblik speaks a plaintext protocol with neither TLS nor authentication — both deliberately absent,
// being incompatible with its zero-copy read path — so anything that can reach the port can read and
// write every topic. It is therefore not published, and "not published" is a thing a reviewer glances
// past and a test does not.
class ComposeStandTest {
    private val compose = Path("../deploy/compose.yaml").readText()

    // Lines from `  <name>:` up to the next key at the same indentation. Written out rather than
    // done with substringBefore, which was the first attempt and silently returned the rest of the
    // file — caught by the "not where this test thinks it is" assertion below, which is why that
    // assertion is there.
    private fun serviceBlock(name: String): String {
        val lines = compose.lines()
        val start = lines.indexOfFirst { it == "  $name:" }
        if (start < 0) return ""
        val end =
            lines
                .drop(start + 1)
                .indexOfFirst { it.isNotBlank() && !it.startsWith("   ") && !it.startsWith("#") }
        return lines.drop(start + 1).let { rest -> if (end < 0) rest else rest.take(end) }.joinToString("\n")
    }

    @Test
    fun `the broker is not reachable from the host`() {
        val broker = serviceBlock("broker")

        assertTrue(broker.contains("booblik"), "the broker service is not where this test thinks it is")
        assertFalse(
            broker.lineSequence().any { it.trimStart().startsWith("- \"") && it.contains(":9092") },
            "the broker publishes a port; it has no authentication and no TLS, so that is the whole of it",
        )
    }

    // WHAT THE BROKER KEEPS, IN THE TWO FILES THAT CONFIGURE IT — the chart for a cluster, the compose
    // file for the stand. Set in one and forgotten in the other is `B-100` one file later: booblik
    // deletes nothing unless one of these is set, and says so at startup in a line nobody reads twice.
    //
    // THE SEGMENT SIZE IS IN HERE and is not decoration. Retention drops whole segments and never the
    // active one, so a bound smaller than one segment deletes nothing at all — a pair of retention
    // values that look right beside booblik's 512 MiB default is a setting that does nothing.
    @Test
    fun `the chart and the stand agree about what the broker keeps`() {
        val values = Path("../charts/konekt/values.yaml").readText()

        val settings =
            mapOf(
                "BOOBLIK_SEGMENT_CAPACITY_BYTES" to "segmentBytes",
                "BOOBLIK_RETENTION_BYTES" to "retentionBytes",
                "BOOBLIK_RETENTION_MILLIS" to "retentionMillis",
            )

        val disagreed =
            settings.mapNotNull { (env, value) ->
                val inStand = numberAfter(compose, "$env:")
                val inChart = numberAfter(values, "$value:")
                when {
                    inStand == null -> "$env is not set in deploy/compose.yaml"
                    inChart == null -> "$value is not set in charts/konekt/values.yaml"
                    inStand != inChart -> "$env is $inStand in the stand and $inChart in the chart"
                    else -> null
                }
            }

        assertTrue(
            disagreed.isEmpty(),
            "the broker's retention differs between the files that configure it:\n" + disagreed.joinToString("\n"),
        )

        // AND THE BOUND MUST EXCEED THE SEGMENT, or retention has nothing it is allowed to delete.
        // Two settings that are individually plausible and jointly useless is the shape this asserts
        // against; without it the pair above passes on numbers that do nothing.
        val segment = numberAfter(compose, "BOOBLIK_SEGMENT_CAPACITY_BYTES:")
        val retained = numberAfter(compose, "BOOBLIK_RETENTION_BYTES:")
        assertTrue(
            segment != null && retained != null && retained > segment,
            "the retention bound ($retained) is not larger than one segment ($segment), so retention " +
                "can never drop anything: it drops whole segments and never the active one",
        )
    }

    private fun numberAfter(
        text: String,
        key: String,
    ): Long? =
        text
            .lineSequence()
            .firstOrNull { it.trimStart().startsWith(key) }
            ?.substringAfter(key)
            ?.trim()
            ?.removeSurrounding("\"")
            ?.toLongOrNull()

    @Test
    fun `the topics the compose file declares are the ones this server routes to`() {
        // The same pairing BrokerTopicsTest makes against a running broker, made here against the
        // file that configures it — so a topic renamed in one place fails even when no container is
        // available to start.
        val declared =
            compose
                .substringAfter("BOOBLIK_TOPICS:")
                .substringBefore('\n')
                .trim()
                .split(",")
                .map { it.substringBefore(':') }

        assertTrue(declared.isNotEmpty(), "BOOBLIK_TOPICS is not in the compose file")
        assertTrue(
            declared.toSet() == EventTopics.all.toSet(),
            "the compose file declares $declared and the server routes to ${EventTopics.all}",
        )
    }
}
