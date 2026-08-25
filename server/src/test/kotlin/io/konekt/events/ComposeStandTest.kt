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
