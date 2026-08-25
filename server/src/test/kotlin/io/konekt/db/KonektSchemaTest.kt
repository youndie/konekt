package io.konekt.db

import io.konekt.db.tables.konektCoreTables
import io.konekt.testing.PostgresHarness
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import ru.workinprogress.petich.postgres.IdempotencyKeysTable
import ru.workinprogress.petich.postgres.OutboxEventsTable
import ru.workinprogress.petich.postgres.PetichTable
import ru.workinprogress.petich.postgres.ScheduledJobsTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The hand-written SQL and the Exposed table definitions are two descriptions of one schema, and
// this is what holds them to each other.
//
// It does not compare the two by reading them — that would be a third description, wrong in its own
// way. It asks EXPOSED, against the database Flyway has just migrated, whether any DDL is still
// required to make the schema match the tables. An empty answer is the only one that means the
// migrations are complete, and it covers the half nobody would think to check by eye: column types,
// nullability, defaults and lengths.
//
// petich's four tables are in here for the reason B-02 exists at all — petich-postgres ships no DDL,
// so if V1 gets a column type wrong the failure surfaces as a saga that cannot be written, in
// whichever feature happens to run one first.
class KonektSchemaTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val petichTables: List<Table> =
        listOf(
            PetichTable(json),
            OutboxEventsTable(),
            IdempotencyKeysTable(),
            ScheduledJobsTable(),
        )

    @Test
    fun `the migrated schema needs no further DDL for petich's tables or ours`() {
        val database = PostgresHarness.database

        val required =
            transaction(database) {
                MigrationUtils.statementsRequiredForDatabaseMigration(*(petichTables + konektCoreTables).toTypedArray())
            }

        // DROP INDEX is filtered out, and the reason is a finding rather than a convenience.
        //
        // MigrationUtils answers "what would make the database EQUAL to these definitions", which is
        // a stronger claim than the one worth asserting: that the schema has everything the code
        // needs. An index the definitions do not mention is not a defect — it is a deliberate
        // addition for the query planner, and three of ours are asked for by petich's own column
        // comments while being absent from its Table objects (youndie/petich#9). Asserting
        // equality would delete exactly the indexes upstream told us to create.
        //
        // Everything else stays a failure, including a column type, a length, a nullability, a
        // default and a constraint name — which is the half nobody would catch by eye.
        val insufficient = required.filterNot { it.trimStart().startsWith("DROP INDEX", ignoreCase = true) }

        assertEquals(
            emptyList(),
            insufficient,
            "Flyway's schema does not satisfy the Exposed tables; still required:\n" +
                insufficient.joinToString("\n"),
        )
    }

    @Test
    fun `the indexes the definitions do not know about are actually there`() {
        // The guard on the filter above. Dropping DROP INDEX from the comparison means an index that
        // is MISSING would also go unnoticed, so each one is checked by name — per index rather than
        // by count, because a count passes on three indexes of which two are the wrong ones.
        val expected =
            listOf(
                "idx_petiches_status_suspended_until",
                "idx_outbox_events_status_created_at",
                "idx_scheduled_jobs_active_next_run_at",
            )

        val present = mutableSetOf<String>()
        PostgresHarness.dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT indexname FROM pg_indexes WHERE schemaname = 'public'").use { rows ->
                    while (rows.next()) present += rows.getString(1)
                }
            }
        }

        expected.forEach { index ->
            assertTrue(index in present, "$index is missing from the migrated schema")
        }
    }

    @Test
    fun `the schema test is looking at something`() {
        // The guard on the guard. statementsRequiredForDatabaseMigration returns an empty list both
        // when everything matches and when it was handed no tables, and the first assertion cannot
        // tell those apart. Seven is petich's four plus konekt's three; the number is asserted here
        // so that a table dropped from either list fails loudly rather than shrinking the check.
        assertEquals(7, (petichTables + konektCoreTables).size)
    }
}
