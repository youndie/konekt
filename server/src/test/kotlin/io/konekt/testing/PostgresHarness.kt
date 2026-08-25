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
    fun truncateAll() {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    TRUNCATE TABLE petiches, outbox_events, idempotency_keys, scheduled_jobs,
                                   esim, account, subscriber
                    RESTART IDENTITY CASCADE
                    """.trimIndent(),
                )
            }
            connection.commit()
        }
    }
}
