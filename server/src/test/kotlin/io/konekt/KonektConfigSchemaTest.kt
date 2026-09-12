package io.konekt

import io.github.youndie.kore.config.ConfigurationException
import io.github.youndie.kore.config.Environment
import io.github.youndie.kore.config.printConfig
import kotlin.io.path.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// THE SCHEMA AND THE FILES THAT SET IT, PAIRED — the same shape as `ComposeStandTest` and for the
// same reason. A variable a chart sets and the process does not declare is a setting somebody wrote
// and nothing reads, and before `konekt#35` that was not a failure anywhere: `DB_POOL_SIZE` had been
// exactly that, passed by a measurement script into a process whose pool size was a Kotlin default.
//
// Now it is a startup refusal in a deployment. This test is what makes it a laptop failure instead.
class KonektConfigSchemaTest {
    private val declared =
        KonektSchema.SCHEMA.keys
            .map { KonektSchema.SCHEMA.variableOf(it) }
            .toSet()

    private val deploymentFiles =
        listOf(
            "../deploy/compose.yaml",
            "../deploy/compose.rolling.yaml",
            "../charts/konekt/templates/server.yaml",
            "../charts/konekt/templates/_helpers.tpl",
            "../scripts/measure/crac-restore.sh",
            "../scripts/measure/aot-coldstart-jib.sh",
            "../scripts/measure/observe.sh",
        )

    @Test
    fun `every prefixed variable a deployment file sets is declared in the schema`() {
        val prefixed = Regex("KONEKT_[A-Z][A-Z0-9_]*")

        deploymentFiles.forEach { path ->
            val text = Path(path).readText()
            assertTrue(text.isNotBlank(), "$path is empty — this test is reading the wrong place")

            val used = prefixed.findAll(text).map { it.value }.toSet()
            val unknown = used - declared

            assertEquals(
                emptySet(),
                unknown,
                "$path sets ${unknown.joinToString()} under the KONEKT_ prefix and the schema does not " +
                    "declare it — the process refuses to start on an undeclared variable under its own prefix",
            )
        }
    }

    // THE OTHER DIRECTION, and it is deliberately NOT a test. A declared variable that no deployment
    // file sets is the normal case — most of them have defaults and exist so that one deployment can
    // differ from another. Asserting the reverse containment would make adding an optional setting a
    // failing build.

    // THE KUBELET WRITES INTO THIS PREFIX UNLESS TOLD NOT TO, and that is the one line this whole
    // schema rests on in a cluster.
    //
    // For every Service in the namespace the kubelet injects `<NAME>_SERVICE_HOST`, `<NAME>_PORT` and
    // a `<NAME>_PORT_<port>_TCP*` group, named after the Service. This chart's server Service is
    // `{{ .Release.Name }}` — `konekt` for the deployment this repository describes — so those land
    // as `KONEKT_PORT`, `KONEKT_SERVICE_HOST` and friends: one of them collides with a declared key
    // whose type is `int`, and the rest are undeclared under the prefix. Both are a pod that will not
    // start, and neither is visible from anything in the server's own sources.
    @Test
    fun `the server pod does not inherit the kubelet's service links`() {
        val chart = Path("../charts/konekt/templates/server.yaml").readText()

        assertContains(
            chart,
            "enableServiceLinks: false",
            message =
                "the pod would be handed KONEKT_PORT=tcp://<ip>:8080 by the kubelet, because the server " +
                    "Service is named after the release and the release is named konekt",
        )
    }

    // Everything with no default, so that a schema read gets as far as the thing each test is about.
    private fun required(): MutableMap<String, String> =
        mutableMapOf(
            "KONEKT_DB_URL" to "jdbc:postgresql://postgres:5432/konekt",
            "KONEKT_DB_USER" to "konekt",
            "KONEKT_DB_PASSWORD" to "a-database-password-that-must-not-be-printed",
            "KONEKT_JWT_SECRET" to "dev-secret-not-for-anything-real",
            // REQUIRED, and it is kore's key rather than konekt's old defaulted one. There is no
            // registration step in any of the three agents, so the service name IS the identifier: a
            // typo does not fail, it files everything under a phantom service that looks healthy and
            // receives nothing. That is why it has no default, and why every deployment path — the
            // migrate container included — now names it.
            "KONEKT_SERVICE" to "konekt-server",
        )

    @Test
    fun `the minimum a deployment must set is the database, the signing secret and the service name`() {
        val config = KonektConfig.fromEnv(Environment.of(required()))

        assertEquals(8080, config.port)
        assertEquals(10, config.database.maximumPoolSize)
        assertFalse(config.simulateTraffic, "an unset switch must be the closed position")
        assertEquals(90, config.simulatedArrivalAfter.inWholeSeconds)
        assertEquals(null, config.observability.metrikWindow, "unset leaves metrik's own default")
    }

    // A MISSPELLED VARIABLE USED TO BE SILENT, and this is the test that says it is not. Measured
    // rather than asserted: the refusal is produced by reading a schema, not by a string this test
    // also wrote.
    @Test
    fun `a misspelled variable under the prefix refuses the start and names the one that was meant`() {
        val environment = required().also { it["KONEKT_SIMULATE_TRAFIC"] = "true" }

        val failure = assertFailsWith<ConfigurationException> { KonektConfig.fromEnv(Environment.of(environment)) }

        assertEquals(1, failure.problems.size, "one misspelling, one problem: ${failure.problems}")
        assertEquals("KONEKT_SIMULATE_TRAFIC", failure.problems.single().variable)
        assertContains(failure.problems.single().message, "KONEKT_SIMULATE_TRAFFIC")
    }

    // The same class of defect one level up: `PAYMENT_MOCK_DELAY_MS=1s` used to be zero and
    // `SIMULATED_ARRIVAL_AFTER_SECONDS=ten` used to be ninety, because both readings ended in
    // `?.toLongOrNull() ?:`.
    @Test
    fun `a value that does not parse refuses the start rather than falling back`() {
        val environment =
            required().also {
                it["KONEKT_PAYMENT_MOCK_DELAY_MS"] = "1s"
                it["KONEKT_SIMULATED_ARRIVAL_AFTER_SECONDS"] = "ten"
            }

        val failure = assertFailsWith<ConfigurationException> { KonektConfig.fromEnv(Environment.of(environment)) }

        assertEquals(
            setOf("KONEKT_PAYMENT_MOCK_DELAY_MS", "KONEKT_SIMULATED_ARRIVAL_AFTER_SECONDS"),
            failure.problems.map { it.variable }.toSet(),
            "both problems at once, because fixing them one restart each is what this replaces",
        )
    }

    // An agent is both variables or neither — the rule that used to be hand-written in
    // `ObservabilityConfig` — deleted in this stage — and threw on the FIRST bad agent, so a deployment that had two of them
    // wrong found out about the second one on the next restart.
    @Test
    fun `half a configured agent refuses the start, and every half at once`() {
        val environment =
            required().also {
                it["KONEKT_TRACY_ENDPOINT"] = "http://tracy:8080"
                it["KONEKT_METRIK_KEY"] = "dev-metrik-key"
            }

        val failure = assertFailsWith<ConfigurationException> { KonektConfig.fromEnv(Environment.of(environment)) }

        assertEquals(
            setOf("KONEKT_TRACY_KEY", "KONEKT_METRIK_ENDPOINT"),
            failure.problems.map { it.variable }.toSet(),
        )
    }

    @Test
    fun `a whole agent is read, and both of the others stay off`() {
        val environment =
            required().also {
                it["KONEKT_TRACY_ENDPOINT"] = "http://tracy:8080"
                it["KONEKT_TRACY_KEY"] = "dev-tracy-key"
            }

        val observability = KonektConfig.fromEnv(Environment.of(environment)).observability

        assertEquals("http://tracy:8080", observability.tracy?.endpoint)
        assertEquals("dev-tracy-key", observability.tracy?.key)
        assertEquals(null, observability.metrik)
        assertEquals(null, observability.katcher)
        assertTrue(observability.anyAgentOn)
    }

    // WHAT `--print-config` MAY NOT PRINT. The masking is a property of the declaration — `secret`
    // on the key — rather than a list of names kept in step with the schema, and this is the test
    // that a key added without it is caught.
    @Test
    fun `print-config shows every variable and none of the secrets`() {
        val environment =
            required().also {
                it["KONEKT_TRACY_ENDPOINT"] = "http://tracy:8080"
                it["KONEKT_TRACY_KEY"] = "a-tracy-key-that-must-not-be-printed"
            }

        val printed = KonektSchema.SCHEMA.printConfig(Environment.of(environment))

        assertEquals(0, printed.exitCode, "this configuration starts, so the flag exits zero")
        assertContains(printed.text, "KONEKT_TRACY_ENDPOINT")
        assertFalse(
            printed.text.contains("dev-secret-not-for-anything-real"),
            "the signing secret was printed",
        )
        assertFalse(
            printed.text.contains("a-tracy-key-that-must-not-be-printed"),
            "an agent key was printed",
        )
        assertFalse(
            printed.text.contains("a-database-password-that-must-not-be-printed"),
            "the database password was printed",
        )
    }

    // The flag exists FOR the unusable case: a route needs a process that started, and this is asked
    // most often because the process did not.
    @Test
    fun `print-config prints what it resolved even when the configuration will not start`() {
        val printed = KonektSchema.SCHEMA.printConfig(Environment.of(mapOf("KONEKT_BRAND" to "brand-b")))

        assertEquals(1, printed.exitCode)
        assertContains(printed.text, "brand-b", message = "it printed nothing it did resolve")
        assertContains(printed.text, "KONEKT_DB_URL")
    }
}
