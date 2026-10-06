package io.konekt.db

import io.konekt.testing.PostgresHarness
import kotlin.test.Test
import kotlin.test.assertEquals

// B-133: the contract half of B-132. Nothing has written `saga_sweep_claim` since `ClaimedSweep` went,
// and after the migrations the table is not there to be written to by mistake either.
class SagaSweepClaimIsGoneTest {
    @Test
    fun `the migrated schema has no saga_sweep_claim`() {
        val count =
            PostgresHarness.dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement
                        .executeQuery(
                            "select count(*) from information_schema.tables " +
                                "where table_schema = current_schema() and table_name = 'saga_sweep_claim'",
                        ).use { rows ->
                            rows.next()
                            rows.getInt(1)
                        }
                }
            }
        assertEquals(0, count, "saga_sweep_claim survived the migrations")
    }
}
