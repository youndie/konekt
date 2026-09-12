package io.konekt.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import javax.sql.DataSource

// How this application gets a database, and the order the two halves happen in.
//
// petich-postgres takes an Exposed `Database` and ships no driver, no pool, no DDL and no
// migrations — so all four are here. That is not an omission upstream: the module deliberately does
// not know which DBMS is underneath, which is the only reason it can be used against anything.
// NOTHING HERE READS THE ENVIRONMENT. `KonektSchema` declares every variable the process has, and
// this module is below the one that owns it — a second reader would be a second place a database
// address can come from, which is how a deployment comes to migrate one database and serve another.
//
// There used to be a `fromEnv()` on this type and it had NO CALLERS: `KonektConfig` built the
// three-argument constructor and took the Kotlin default for the pool. `DB_POOL_SIZE` was therefore
// a variable that existed in a script and in no running process (`konekt#35`).
data class DatabaseConfig(
    val url: String,
    val user: String,
    val password: String,
    val maximumPoolSize: Int = 10,
)

object DatabaseFactory {
    fun dataSource(config: DatabaseConfig): DataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = config.url
                username = config.user
                password = config.password
                driverClassName = "org.postgresql.Driver"
                maximumPoolSize = config.maximumPoolSize
                // The pool is not the place to discover a wrong password. Fail while starting.
                initializationFailTimeout = 10_000
                isAutoCommit = false
                transactionIsolation = "TRANSACTION_READ_COMMITTED"
            },
        )

    // Applying migrations is a step of its own, run BEFORE anything serves. During a rolling deploy
    // the schema has to be current when the first new process comes up, and two processes must never
    // race to migrate — so this is what the migrate-only entry point calls, and the application
    // itself does not call it.
    //
    // `lockTimeout` is a session setting rather than a Flyway one: an ALTER TABLE waiting for a lock
    // queues every reader behind it, and a blocked table is downtime whatever the deploy is doing.
    fun migrate(dataSource: DataSource): Int =
        Flyway
            .configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            // Refuse to start on a schema that was migrated by a LATER version of this application.
            // The alternative is a new pod quietly serving against a schema it does not understand.
            .validateOnMigrate(true)
            // No `baselineOnMigrate`: it turns "this database has tables I did not create" into
            // "assume everything before now is fine", which is the wrong answer for every case
            // except a one-off adoption that has not happened here.
            .cleanDisabled(true)
            // THE SECOND HALF OF THE CONCURRENT-INDEX RECIPE, and the half whose absence is a HANG
            // rather than a failure. A migration opts out of its transaction with a
            // `V<n>__<desc>.sql.conf` carrying `executeInTransaction=false`, which
            // CREATE INDEX CONCURRENTLY requires — and that alone deadlocks on PostgreSQL, because
            // Flyway's own lock is transactional and waits on the index build that is waiting on it.
            // During a deploy a deadlock reads as a slow rollout, which is the most expensive way for
            // this to go wrong. See research-stack §1.6 and flyway/flyway#3840.
            //
            // Set here rather than per migration: it is a property of how Flyway takes its lock, not
            // of any one script, and a setting that has to be repeated is a setting that gets
            // forgotten on the one script that needed it.
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false"))
            .load()
            .migrate()
            .migrationsExecuted

    fun connect(dataSource: DataSource): Database = Database.connect(dataSource)
}
