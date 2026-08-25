package io.konekt.db

import io.konekt.db.tables.konektCoreTables
import io.konekt.feature.auth.server.data.OtpChallengeTable
import io.konekt.feature.auth.server.data.RefreshTokenTable
import io.konekt.feature.auth.server.data.SessionFamilyTable
import io.konekt.feature.esim.server.data.EsimWizardSessionTable
import io.konekt.feature.purchase.server.data.EntitlementTable
import io.konekt.feature.purchase.server.data.LedgerEntryTable
import io.konekt.feature.usage.server.data.UsageCounterTable
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
                MigrationUtils.statementsRequiredForDatabaseMigration(*allTables.toTypedArray())
            }

        // Strict equality, with no exemption.
        //
        // There used to be one: DROP INDEX was filtered out, because petich asked for three indexes
        // in column comments and declared none, so Exposed's view of the schema did not contain them
        // and wanted ours dropped. youndie/petich#9 closed that in 0.1.0.8 — the three are declared
        // now, under the same names — so the exemption is no longer earned and is gone.
        //
        // Removing it matters more than adding it did. An exemption in a completeness check describes
        // the one case it was written for and is blind to the next thing that looks like it; while
        // DROP INDEX was ignored here, an index that genuinely should have gone would have been
        // ignored too.
        assertEquals(
            emptyList(),
            required,
            "Flyway's schema does not match the Exposed tables; still required:\n" + required.joinToString("\n"),
        )
    }

    // Every table in the build, so a feature's tables are covered by the same check as the core's.
    // A feature that adds a table and not a line here is a feature whose migration nobody verified.
    private val allTables: List<Table> get() =
        petichTables + konektCoreTables +
            listOf(
                OtpChallengeTable,
                SessionFamilyTable,
                RefreshTokenTable,
                EntitlementTable,
                LedgerEntryTable,
                UsageCounterTable,
                EsimWizardSessionTable,
            )

    @Test
    fun `the schema test is looking at something`() {
        // The guard on the guard. statementsRequiredForDatabaseMigration returns an empty list both
        // when everything matches and when it was handed no tables, and the first assertion cannot
        // tell those apart. Fourteen is petich's four, konekt's three core tables, the auth feature's
        // three, the purchase feature's two, usage's one and the eSIM wizard's one; the number is
        // asserted here so that a table dropped from any list fails loudly rather than shrinking the
        // check.
        assertEquals(14, allTables.size)
    }
}
