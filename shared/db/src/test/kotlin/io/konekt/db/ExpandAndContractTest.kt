package io.konekt.db

import kotlin.io.path.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// D22 as a gate rather than as a paragraph.
//
// During a rolling deploy both versions of the code run against ONE schema, and there is no moment at
// which only one of them does. So a migration is not judged by whether it produces the right schema —
// it is judged by whether the code ALREADY RUNNING survives it. That distinction is invisible in
// staging, where one process is replaced instantly, and it is the whole failure in production.
//
// The rule was going to be enforced by review, and review holds until the week somebody is in a hurry.
// It is made worse by the generator: `generateMigrations` emits the SHORTEST SQL that makes two
// schemas equal, which is `DROP COLUMN`, `RENAME` and `ALTER … TYPE` — precisely the set that breaks a
// roll. The draft looks finished, which is what makes a reviewer agree with it.
class ExpandAndContractTest {
    private val migrations = Path("src/main/resources/db/migration")

    private fun files() =
        migrations
            .listDirectoryEntries()
            .filter { !it.isDirectory() && it.name.endsWith(".sql") }
            .sortedBy { it.name }

    // Anything that takes something away from a schema the previous release may still be using. A
    // `DROP INDEX` is not here: an index is invisible to a query's meaning, so dropping one slows the
    // old code rather than breaking it.
    private val destructive =
        mapOf(
            "DROP COLUMN" to Regex("""(?i)\bDROP\s+COLUMN\b"""),
            "DROP TABLE" to Regex("""(?i)\bDROP\s+TABLE\b"""),
            "RENAME" to Regex("""(?i)\bRENAME\s+(COLUMN|TO)\b"""),
            "ALTER TYPE" to Regex("""(?i)\bALTER\s+(COLUMN\s+\w+\s+)?TYPE\b"""),
            "SET NOT NULL" to Regex("""(?i)\bSET\s+NOT\s+NULL\b"""),
        )

    // The one way to say "this is the second half of a pair, and here is the first". It names the
    // expand migration, so the claim is checkable rather than a promise: the reviewer can read what
    // was added and when reading of the old column stopped.
    private val contractMarker = Regex("""(?m)^--\s*contract:\s*expanded in (V[\w.]+)\b""")

    @Test
    fun `nothing is taken away without naming the release that stopped using it`() {
        val offenders =
            files().flatMap { file ->
                val text = file.readText()
                val marker = contractMarker.find(text)
                destructive
                    .filter { (_, pattern) -> pattern.containsMatchIn(text) }
                    .keys
                    .filter { marker == null }
                    .map { "${file.name}: $it" }
            }

        assertEquals(
            emptyList(),
            offenders,
            "these take something away and do not say which migration expanded it first. During a " +
                "roll the previous release is still reading it. Split the change into a pair (D22) " +
                "and mark the second half:\n" +
                "  -- contract: expanded in V<n>, and nothing has read it since <release>\n" +
                offenders.joinToString("\n"),
        )
    }

    @Test
    fun `a contract migration names an expand that exists and came earlier`() {
        val versions = files().map { it.name.substringBefore("__") }

        files().forEach { file ->
            val marker = contractMarker.find(file.readText()) ?: return@forEach
            val expand = marker.groupValues[1]
            val here = file.name.substringBefore("__")

            // A marker naming a migration that does not exist is a marker that was never checked, and
            // one naming a LATER migration is a pair written backwards — the contract landing before
            // the expand it claims to follow.
            assertTrue(expand in versions, "${file.name} names $expand, which is not a migration here")
            assertTrue(expand < here, "${file.name} claims to contract $expand, which comes after it")
        }
    }

    @Test
    fun `every migration bounds how long it will wait for a lock`() {
        val offenders =
            files()
                .filterNot { Regex("""(?i)\bSET\s+lock_timeout\b""").containsMatchIn(it.readText()) }
                .map { it.name }

        assertEquals(
            emptyList(),
            offenders,
            "these set no lock_timeout. A statement waiting for a lock queues every later reader " +
                "behind it, and a blocked table is downtime whatever the deploy is doing:\n" +
                offenders.joinToString("\n"),
        )
    }

    @Test
    fun `a concurrent index carries both settings, because one of them is a hang`() {
        val offenders =
            files()
                .filter { Regex("""(?i)\bCONCURRENTLY\b""").containsMatchIn(it.readText()) }
                .mapNotNull { file ->
                    val sidecar = migrations.resolve("${file.name}.conf")
                    val configuration = if (sidecar.toFile().exists()) sidecar.readText() else ""

                    val missing =
                        listOfNotNull(
                            "executeInTransaction=false".takeIf { it !in configuration.replace(" ", "") },
                            // WITHOUT THIS ONE IT HANGS RATHER THAN FAILING. Flyway's own lock is
                            // transactional and deadlocks against the concurrent build, and during a
                            // deploy a hang reads as a slow rollout — the failure mode that costs the
                            // most to diagnose.
                            "postgresql.transactional.lock=false".takeIf {
                                it !in configuration.replace(" ", "")
                            },
                        )

                    if (missing.isEmpty()) null else "${file.name}.conf is missing ${missing.joinToString(", ")}"
                }

        assertEquals(emptyList(), offenders, offenders.joinToString("\n"))
    }

    @Test
    fun `the gate is looking at something`() {
        // Every assertion above passes on an empty directory, and a moved resources path would produce
        // exactly that.
        assertTrue(files().size >= 8, "found ${files().size} migrations — is the path right?")
        // And the destructive patterns must actually match destructive SQL, or the first test is a
        // regex that fires on nothing.
        assertTrue(
            destructive.values.all {
                it.containsMatchIn(
                    "ALTER TABLE t DROP COLUMN c; ALTER TABLE t RENAME TO u; " +
                        "ALTER TABLE t ALTER COLUMN c TYPE text; ALTER TABLE t ALTER COLUMN c SET NOT NULL; DROP TABLE t;",
                )
            },
            "a destructive pattern no longer matches the SQL it names",
        )
    }
}
