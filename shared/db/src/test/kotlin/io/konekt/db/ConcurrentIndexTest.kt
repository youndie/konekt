package io.konekt.db

import io.konekt.testing.PostgresHarness
import org.flywaydb.core.Flyway
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertTrue

// The recipe D22 prescribes for adding an index to a live table, run rather than described.
//
// On a THROWAWAY schema rather than a product migration, deliberately: no committed migration needs a
// concurrent index yet, and inventing one so a test has something to look at would put a change into
// the schema to satisfy a test. What is under test is the recipe — two settings, one of which is the
// difference between a failure and a hang.
//
// IT MEASURES BOTH VARIANTS IN ONE RUN AND COMPARES THEM. An absolute threshold in milliseconds
// measures the runner, not the property: the first version of this test asserted that a plain
// CREATE INDEX blocks a writer for at least 200ms and the Linux box did it in 148, which says
// something about the box and nothing about the index.
class ConcurrentIndexTest {
    private val scripts = Files.createTempDirectory("konekt-concurrent-index")

    private fun freshSchema(): String {
        val schema = "cidx_${UUID.randomUUID().toString().take(8).replace("-", "")}"

        PostgresHarness.dataSource.connection.use { connection ->
            // EXPLICIT, because the pool hands out connections with autocommit off: the CREATE would
            // roll back when the connection went home, and the writer would then meet a table that
            // does not exist — an error that names the relation rather than the transaction.
            connection.autoCommit = true
            connection.createStatement().use { statement ->
                statement.execute("CREATE SCHEMA $schema")
                statement.execute("CREATE TABLE $schema.reading (id BIGSERIAL PRIMARY KEY, value TEXT NOT NULL)")
            }
            connection
                .prepareStatement(
                    "INSERT INTO $schema.reading (value) SELECT md5(g::text) FROM generate_series(1, $ROWS) g",
                ).use { it.execute() }
        }

        return schema
    }

    private fun drop(schema: String) {
        PostgresHarness.dataSource.connection.use { connection ->
            connection.autoCommit = true
            connection.createStatement().use { it.execute("DROP SCHEMA IF EXISTS $schema CASCADE") }
        }
    }

    private fun migrate(
        schema: String,
        concurrently: Boolean,
    ): Int {
        scripts.resolve("V1__index.sql").writeText(
            "SET lock_timeout = '${LOCK_TIMEOUT_SECONDS}s';\n" +
                "CREATE INDEX ${if (concurrently) "CONCURRENTLY " else ""}idx_reading_value " +
                "ON $schema.reading (value);\n",
        )
        // The sidecar takes the statement OUT of a transaction, which CREATE INDEX CONCURRENTLY
        // requires. Written for this variant only, so the plain one runs the ordinary way.
        val sidecar = scripts.resolve("V1__index.sql.conf")
        if (concurrently) sidecar.writeText("executeInTransaction=false\n") else Files.deleteIfExists(sidecar)

        return Flyway
            .configure()
            .dataSource(PostgresHarness.dataSource)
            .locations("filesystem:$scripts")
            .schemas(schema)
            .defaultSchema(schema)
            .table("flyway_index_probe")
            // The schema already holds the table being indexed, which Flyway reads as a database
            // somebody wants adopted. Right for a probe schema and refused in DatabaseFactory, which
            // says why.
            .baselineOnMigrate(true)
            .baselineVersion("0")
            // The other half of the recipe, by the same route DatabaseFactory uses: a configuration
            // property, because that is what a deployment can put in an environment variable. Without
            // it, Flyway's transactional lock deadlocks against the concurrent build and the
            // migration HANGS — which during a deploy reads as a slow rollout.
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false"))
            .load()
            .migrate()
            .migrationsExecuted
    }

    private data class Observed(
        val writes: Int,
        val worstMillis: Long,
    )

    // Hammers the table throughout the index build and reports the longest single write it saw.
    private fun whileWriting(
        schema: String,
        block: () -> Unit,
    ): Observed {
        val running = AtomicBoolean(true)
        val writes = AtomicInteger(0)
        val worst = AtomicLong(0)
        val pool = Executors.newSingleThreadExecutor()

        val writer =
            pool.submit {
                PostgresHarness.dataSource.connection.use { connection ->
                    connection.autoCommit = true
                    connection.prepareStatement("INSERT INTO $schema.reading (value) VALUES (?)").use { statement ->
                        while (running.get()) {
                            val started = System.nanoTime()
                            statement.setString(1, "live")
                            statement.executeUpdate()
                            worst.updateAndGet { previous ->
                                maxOf(previous, (System.nanoTime() - started) / 1_000_000)
                            }
                            writes.incrementAndGet()
                        }
                    }
                }
            }

        try {
            block()
        } finally {
            running.set(false)
            writer.get(60, TimeUnit.SECONDS)
            pool.shutdown()
        }

        return Observed(writes.get(), worst.get())
    }

    @Test
    fun `a concurrent index lets the writers through and a plain one does not`() {
        val concurrent = freshSchema()
        val plain = freshSchema()

        val withConcurrently: Observed
        val withoutConcurrently: Observed
        try {
            withConcurrently = whileWriting(concurrent) { migrate(concurrent, concurrently = true) }
            // THE CONTROL, in the same run and on the same machine. Without it the first measurement
            // is only "writes happened", and a plain CREATE INDEX that also let them through would
            // mean the comparison cannot tell the two apart.
            withoutConcurrently = whileWriting(plain) { migrate(plain, concurrently = false) }
        } finally {
            drop(concurrent)
            drop(plain)
        }

        assertTrue(withConcurrently.writes > 0, "no write landed during the concurrent build")
        assertTrue(withoutConcurrently.writes > 0, "the control writer never ran, so it measured nothing")

        // RELATIVE, because the two were measured on the same machine seconds apart. A plain build
        // takes a SHARE lock and every INSERT queues behind it; a concurrent one does not.
        assertTrue(
            withoutConcurrently.worstMillis > withConcurrently.worstMillis * 4,
            "a plain CREATE INDEX blocked writers for ${withoutConcurrently.worstMillis}ms and a " +
                "concurrent one for ${withConcurrently.worstMillis}ms — too close to tell apart, so " +
                "this measured something other than the lock",
        )

        // And the absolute claim that matters on its own: nothing waited anywhere near the bound this
        // repository sets on every migration.
        assertTrue(
            withConcurrently.worstMillis < LOCK_TIMEOUT_SECONDS * 1_000 / 3,
            "a write waited ${withConcurrently.worstMillis}ms against a ${LOCK_TIMEOUT_SECONDS}s timeout",
        )
    }

    private companion object {
        // Enough that a plain build takes measurable time. Over an empty table it finishes before a
        // writer could be blocked, and the control would pass having asked nothing.
        const val ROWS = 400_000
        const val LOCK_TIMEOUT_SECONDS = 3
    }
}
