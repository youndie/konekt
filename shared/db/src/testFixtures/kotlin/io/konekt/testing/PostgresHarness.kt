package io.konekt.testing

import io.konekt.db.DatabaseFactory
import org.jetbrains.exposed.v1.jdbc.Database
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import javax.sql.DataSource

// A real Postgres, of the major the deployment runs, migrated by the real Flyway migrations.
//
// NOT H2, and not a mock. Mocking Exposed proves nothing — the defect a repository test exists for
// lives in the SQL, and a mock returns whatever the test put in it. H2's Postgres compatibility mode
// is cheaper and diverges on exactly what this build leans on: ON CONFLICT, SELECT ... FOR UPDATE
// beside petich's optimistic lock, and the JSON column a saga payload lives in.
//
// One container for the whole test JVM rather than one per class. Starting Postgres costs a second
// or two, and a suite that pays that per class is a suite people stop running. Ryuk removes it when
// the JVM exits; tests share it and clean their own rows.
object PostgresHarness {
    // Pinned to a major, not to `latest`: a test stand that silently changes DBMS version is a test
    // stand that answers a different question every few months.
    private const val IMAGE = "postgres:18-alpine"

    private val container: PostgreSQLContainer<Nothing> =
        PostgreSQLContainer<Nothing>(DockerImageName.parse(IMAGE)).apply {
            withDatabaseName("konekt")
            withUsername("konekt")
            withPassword("konekt")
            start()
        }

    val dataSource: DataSource by lazy {
        DatabaseFactory
            .dataSource(
                io.konekt.db.DatabaseConfig(
                    url = container.jdbcUrl,
                    user = container.username,
                    password = container.password,
                    maximumPoolSize = 4,
                ),
            ).also { source ->
                val applied = DatabaseFactory.migrate(source)
                // A migration count of zero would mean Flyway found no scripts — a classpath
                // mistake that otherwise shows up much later as "relation does not exist", pointing
                // at the query rather than at the cause.
                check(applied > 0) { "Flyway applied no migrations — is db/migration on the classpath?" }
            }
    }

    val database: Database by lazy { DatabaseFactory.connect(dataSource) }

    // Between tests, not between classes: the container is shared, so a test that leaves rows behind
    // is a test that breaks the next one in a way that depends on execution order.
    // ASKED FOR, NOT RETYPED, and the list it replaces is why.
    //
    // This was thirteen table names written out by hand, and by the time `B-92` needed a fourteenth
    // the list was already three short: `tariff_change` (B-21), `roaming_package` (B-19) and
    // `esim_wizard_session` (B-51) were never added. Nothing failed — a test that leaves rows behind
    // breaks the NEXT one, in a way that depends on execution order, which is the hardest failure in
    // this repository to attribute and the easiest to blame on flakiness.
    //
    // `flyway_schema_history` is the one table that must survive: truncating it makes Flyway rerun
    // every migration against a schema that already has them.
    fun truncateAll() {
        dataSource.connection.use { connection ->
            val tables =
                connection.createStatement().use { statement ->
                    statement
                        .executeQuery(
                            """
                            SELECT table_name FROM information_schema.tables
                            WHERE table_schema = current_schema()
                              AND table_type = 'BASE TABLE'
                              AND table_name <> 'flyway_schema_history'
                            """.trimIndent(),
                        ).use { rows ->
                            generateSequence { if (rows.next()) rows.getString(1) else null }.toList()
                        }
                }

            // Vacuity: an empty list truncates nothing and every test after it starts dirty. It can
            // only happen if the query is wrong, and it would look exactly like a flaky suite.
            check(tables.isNotEmpty()) { "no tables found to truncate — has the schema query stopped matching?" }

            connection.createStatement().use { statement ->
                statement.execute(
                    tables.joinToString(", ", prefix = "TRUNCATE TABLE ", postfix = " RESTART IDENTITY CASCADE") {
                        // Quoted: a table named after a reserved word is a migration away, and the
                        // failure would be a syntax error in a helper nobody is looking at.
                        "\"$it\""
                    },
                )
            }
            connection.commit()
        }
    }
}
