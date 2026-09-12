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
// IT ASKS POSTGRES WHETHER THE WRITER QUEUED, and that is the third shape this assertion has had.
//
// The first compared milliseconds against a fixed threshold — a plain build had to block a writer for
// at least 200ms — and the Linux box did it in 148, which says something about the box and nothing
// about the index. The second compared the two variants to each other in one run, which is better and
// still a measurement: it wanted the plain arm to block four times longer than the concurrent one,
// and on a two-core CI runner it twice could not tell them apart — 276ms against 119ms, then 442ms
// against 232ms, once on `main` (`#43`).
//
// The tell was the concurrent arm. `CREATE INDEX CONCURRENTLY` takes no lock an `INSERT` queues
// behind, so it should have been near zero and was not; whatever stalled that writer was the
// scheduler, not the index. Once the noise is the same order as the effect, a ratio between two small
// numbers is reachable by noise alone, and the harness is measuring the harness.
//
// So the gate is no longer a duration. `pg_blocking_pids` answers the question the recipe is actually
// about — DID A WRITE QUEUE BEHIND THE BUILD — as a fact rather than as a number, and a slow runner
// cannot change the answer. The durations are still collected and still printed in the failure,
// because they are what a person reads to understand what happened; they just do not decide anything.
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
        /** Reported, never asserted on: it is what a person reads, not what decides. */
        val worstMillis: Long,
        /** Whether Postgres ever named somebody as blocking the writer's backend. THE VERDICT. */
        val queued: Boolean,
        /** How many times the observer got to ask. Zero means it measured nothing at all. */
        val polls: Int,
    )

    // Hammers the table throughout the index build, and asks Postgres — from a third connection —
    // whether the writer's backend is waiting on somebody. The durations come along for the failure
    // message; `queued` is the answer.
    private fun whileWriting(
        schema: String,
        block: () -> Unit,
    ): Observed {
        val running = AtomicBoolean(true)
        val writes = AtomicInteger(0)
        val worst = AtomicLong(0)
        val writerPid = AtomicInteger(0)
        val queued = AtomicBoolean(false)
        val polls = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(2)

        val writer =
            pool.submit {
                PostgresHarness.dataSource.connection.use { connection ->
                    connection.autoCommit = true
                    connection.createStatement().use { statement ->
                        statement.executeQuery("SELECT pg_backend_pid()").use {
                            it.next()
                            writerPid.set(it.getInt(1))
                        }
                    }
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

        val observer =
            pool.submit {
                // The writer publishes its backend id before its first INSERT; until then there is
                // nothing to ask about.
                while (running.get() && writerPid.get() == 0) Thread.sleep(1)

                PostgresHarness.dataSource.connection.use { connection ->
                    connection.autoCommit = true
                    // `pg_blocking_pids` is the question the recipe is about, asked of the server:
                    // non-empty means this backend is waiting on a lock somebody else holds. It reads
                    // system views only, so the observer cannot itself be blocked by the build.
                    connection
                        .prepareStatement("SELECT cardinality(pg_blocking_pids(?)) > 0")
                        .use { statement ->
                            statement.setInt(1, writerPid.get())
                            while (running.get()) {
                                statement.executeQuery().use {
                                    it.next()
                                    if (it.getBoolean(1)) queued.set(true)
                                }
                                polls.incrementAndGet()
                                Thread.sleep(POLL_MILLIS)
                            }
                        }
                }
            }

        try {
            block()
        } finally {
            running.set(false)
            writer.get(60, TimeUnit.SECONDS)
            observer.get(60, TimeUnit.SECONDS)
            pool.shutdown()
        }

        return Observed(writes.get(), worst.get(), queued.get(), polls.get())
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

        // THE OBSERVER HAS TO HAVE ASKED. A poll count of zero would make both verdicts below read
        // "nobody was blocked" — which is the right answer for one arm and a vacuous pass for the
        // other, and the two would be indistinguishable. This is the guard on the guard.
        assertTrue(withConcurrently.polls > 0, "the observer never asked during the concurrent build")
        assertTrue(withoutConcurrently.polls > 0, "the observer never asked during the plain build")

        // THE CONTROL, AND IT IS A FACT RATHER THAN A DURATION. A plain CREATE INDEX takes a SHARE
        // lock on the table and every INSERT queues behind it, so Postgres names the builder as
        // blocking the writer. Nothing about this answer depends on how fast the machine is.
        assertTrue(
            withoutConcurrently.queued,
            "a plain CREATE INDEX never blocked a writer — pg_blocking_pids named nobody across " +
                "${withoutConcurrently.polls} polls and ${withoutConcurrently.writes} writes, so the " +
                "control asked nothing and the comparison below is vacuous",
        )

        // AND THE PROPERTY ITSELF. `CONCURRENTLY` takes a ShareUpdateExclusive lock, which an INSERT
        // does not queue behind, so the writer must never once be found waiting.
        assertTrue(
            !withConcurrently.queued,
            "a write queued behind CREATE INDEX CONCURRENTLY — the recipe in D22 does not hold. " +
                "worst write ${withConcurrently.worstMillis}ms across ${withConcurrently.polls} polls " +
                "(plain arm, for contrast: ${withoutConcurrently.worstMillis}ms)",
        )

        // The one duration still asserted on, and it is absolute rather than a ratio: nothing waited
        // anywhere near the bound this repository sets on every migration. A third of the timeout is
        // wide enough that a slow runner does not reach it — the failures in `#43` were 232ms against
        // this 1000ms — and it is a claim worth keeping because a concurrent build that somehow did
        // block would be caught here even if the lock question were answered wrongly.
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

        // Fast enough that a plain build — hundreds of milliseconds over 400k rows — is seen many
        // times over, and slow enough that the observer is not itself a load on the server it is
        // asking. It bounds how briefly a block could hide, not how long anything takes.
        const val POLL_MILLIS = 5L
    }
}
