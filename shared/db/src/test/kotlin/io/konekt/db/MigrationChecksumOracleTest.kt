package io.konekt.db

import io.konekt.testing.PostgresHarness
import org.flywaydb.core.Flyway
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// THE ORACLE FOR THE LOCK FILE. `AppliedMigrationsAreImmutableTest` compares recorded numbers against
// numbers it computes itself, and a guard that checks its own arithmetic against its own arithmetic
// agrees with itself no matter what Flyway does. So this runs the real thing — every migration, on a
// real Postgres — and asserts the numbers Flyway writes into `flyway_schema_history` are the numbers
// in the lock.
//
// What it buys is the failure mode that would otherwise be silent: a Flyway upgrade that changed how
// it hashes a script would leave the lock full of numbers no contour will ever agree with, and every
// other test in this repository would stay green. It also proves the lock's numbers are the ones a
// DEPLOY compares against, rather than a convention this repository invented.
//
// ON ITS OWN SCHEMA rather than the harness's, because it migrates from empty and the shared schema
// is already migrated. `flyway_schema_history` lives in the schema being migrated, so a private one
// gives this test its own history table without touching anybody else's.
class MigrationChecksumOracleTest {
    private fun freshSchema(): String {
        val schema = "oracle_${UUID.randomUUID().toString().take(8).replace("-", "")}"

        PostgresHarness.dataSource.connection.use { connection ->
            connection.autoCommit = true
            connection.createStatement().use { it.execute("CREATE SCHEMA $schema") }
        }
        return schema
    }

    @Test
    fun `flyway records the checksums the lock file holds`() {
        val schema = freshSchema()

        Flyway
            .configure()
            .dataSource(PostgresHarness.dataSource)
            .locations("classpath:db/migration")
            .schemas(schema)
            .defaultSchema(schema)
            .cleanDisabled(true)
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false"))
            .load()
            .migrate()

        val applied = mutableMapOf<String, Long>()
        PostgresHarness.dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    "SELECT script, checksum FROM $schema.flyway_schema_history WHERE checksum IS NOT NULL",
                ).use { statement ->
                    statement.executeQuery().use { rows ->
                        while (rows.next()) applied[rows.getString("script")] = rows.getLong("checksum")
                    }
                }
        }

        // Vacuity: a run that applied nothing — a schema that was somehow already migrated, a
        // location that resolved to no files — would agree with an empty lock about nothing.
        assertTrue(applied.isNotEmpty(), "Flyway recorded no checksums; this test compared two empty sets")

        val recorded =
            this::class.java
                .getResourceAsStream("/applied-migrations.checksums")!!
                .bufferedReader()
                .readLines()
                .filterNot { it.isBlank() || it.startsWith("#") }
                .associate { line -> line.split(" ").let { it[0] to it[1].toLong() } }

        assertEquals(
            recorded.keys.sorted(),
            applied.keys.sorted(),
            "the lock file and the migrations Flyway actually ran are not the same set",
        )
        applied.forEach { (script, checksum) ->
            assertEquals(
                checksum,
                recorded[script],
                "$script: Flyway records $checksum and the lock holds ${recorded[script]}.\n" +
                    "Either the file changed, or this Flyway version hashes differently from the one that\n" +
                    "wrote the lock — the second is worse, because it makes every number in it wrong at once.",
            )
        }
    }
}
