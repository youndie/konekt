package io.konekt.db

import java.util.zip.CRC32
import kotlin.io.path.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// A MIGRATION THAT HAS RUN IS IMMUTABLE, AND THE COMMENTS ARE PART OF IT.
//
// Flyway records a checksum per applied version and refuses to start when the file behind it has
// changed. Nothing in this repository noticed that until a deploy did: V11 shipped, its comment was
// then corrected — no SQL touched — and the next release could not start. The init container
// crash-looped, the deployment never became available, and helm rolled back on a ten-minute timeout
// with a message about readiness. Nothing in that chain says "you edited a migration".
//
// So the check moved to where the edit happens. `applied-migrations.checksums` records the number for
// every file; a new migration adds a line, and a changed number is this test failing.
//
// IT IS NOT A GOLDEN TO REGENERATE. A golden gets rewritten alongside the code it guards, which is
// exactly the gesture that would have let this through — so the failure names the consequence rather
// than the file to update: every contour that already ran the version needs `flyway repair` before
// it will start again. The lock file says the same thing at the top, where somebody about to
// regenerate it is looking.
//
// The arithmetic here is checked against the real thing by `MigrationChecksumOracleTest`, so a
// Flyway upgrade that changed how it hashes would fail there rather than quietly locking numbers no
// contour will ever agree with.
class AppliedMigrationsAreImmutableTest {
    private val migrations = Path("src/main/resources/db/migration")

    private fun recorded(): Map<String, Long> =
        this::class.java
            .getResourceAsStream("/applied-migrations.checksums")
            ?.bufferedReader()
            ?.readLines()
            ?.filterNot { it.isBlank() || it.startsWith("#") }
            ?.associate { line ->
                val (name, checksum) = line.split(" ")
                name to checksum.toLong()
            }
            ?: error("no /applied-migrations.checksums on the test classpath — the guard cannot run")

    private fun scripts() =
        migrations
            .listDirectoryEntries()
            .filter { !it.isDirectory() && it.name.endsWith(".sql") }
            .sortedBy { it.name }

    @Test
    fun `no migration that has already run has changed`() {
        val recorded = recorded()
        // Vacuity first: an empty lock, or one the classpath answered with nothing, would pass every
        // assertion below by having nothing to compare.
        assertTrue(recorded.isNotEmpty(), "the checksum lock is empty — this test would pass on anything")

        scripts().forEach { script ->
            val expected =
                recorded[script.name]
                    ?: fail(
                        "${script.name} has no line in applied-migrations.checksums. A new migration adds one:\n" +
                            "  ${script.name} ${flywayChecksum(script.readText())}",
                    )

            assertEquals(
                expected,
                flywayChecksum(script.readText()),
                "${script.name} changed after it was recorded, and Flyway will refuse to start against it.\n" +
                    "A comment counts: the checksum is over the whole file.\n" +
                    "If the change is genuinely needed, every contour that ran this version needs a `flyway repair`\n" +
                    "before it will boot again — writing a NEW migration is almost always cheaper.",
            )
        }
    }

    // The other direction, because a rename is the same defect wearing different clothes: Flyway keys
    // on the VERSION, so renaming a file leaves the old row in place and the new name is missing.
    @Test
    fun `the lock names no migration that is gone`() {
        val present = scripts().map { it.name }.toSet()

        recorded().keys.forEach { name ->
            assertTrue(
                name in present,
                "applied-migrations.checksums names $name and no such file exists — renamed or deleted.\n" +
                    "Flyway keys on the version, so a contour that ran it still holds the old row.",
            )
        }
    }

    private fun fail(message: String): Nothing = throw AssertionError(message)

    companion object {
        // Flyway's own arithmetic: CRC32 over each line's bytes, line terminators excluded. Kept here
        // rather than called from Flyway because the class that owns it is internal — and kept honest
        // by MigrationChecksumOracleTest, which compares this against what Flyway actually writes.
        fun flywayChecksum(text: String): Long {
            val crc = CRC32()
            text.lines().forEach { crc.update(it.toByteArray()) }
            // Flyway stores it as a signed 32-bit int.
            return crc.value.toInt().toLong()
        }
    }
}
