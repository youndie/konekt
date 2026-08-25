plugins {
    id("konekt.jvm")
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.exposedMigrations)
    application
}

application {
    mainClass.set("io.konekt.ApplicationKt")
}

// `generateMigrations` drafts a Flyway script by diffing the Table definitions against a schema. See
// scripts/generate-migration.sh for how it is actually run, and B-36 for why its output is a draft:
// a differ emits the shortest SQL that makes two schemas equal, which is DROP COLUMN and RENAME —
// exactly what breaks a rolling deploy.
exposed {
    migrations {
        // A single package ROOT. Every Table in every feature module has to sit under it, which is
        // why the package layout of this repository is `io.konekt.*` without exception.
        tablesPackage.set("io.konekt.db.tables")
        // Against a throwaway Postgres with the committed migrations already applied, so the draft
        // accounts for everything in db/migration rather than for whatever a developer's local
        // database happens to hold.
        testContainersImageName.set("postgres:18-alpine")
        // Drafts land in build/, never straight into db/migration. What the differ writes is the
        // shortest SQL that makes two schemas equal — DROP COLUMN, RENAME — which is exactly what
        // breaks a rolling deploy, so a human turns it into an expand/contract pair before it is
        // committed (B-36).
        fileDirectory.set(layout.buildDirectory.dir("generated-migrations"))
        filePrefix.set("V")
        fileSeparator.set("__")
        fileExtension.set(".sql")
    }
}

tasks.withType<Test>().configureEach {
    // MigrationFilesTest reads src/main/resources/db/migration as a directory, so it needs the
    // module root. Stated rather than assumed: a Gradle default that moved would make the test read
    // an empty directory, which is why it also asserts it found something.
    workingDir = projectDir
}

dependencies {
    implementation(project(":shared:domain"))

    // One platform, and no kompot coordinate below names a version. See gradle/libs.versions.toml.
    implementation(platform(libs.kompot.bom))
    implementation(libs.kompot.core)
    implementation(libs.kompot.standard)
    implementation(libs.kompot.ktor)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.resources)
    implementation(libs.ktor.server.contentNegotiation)
    implementation(libs.ktor.server.statusPages)
    implementation(libs.ktor.server.callLogging)
    implementation(libs.ktor.serialization.json)

    implementation(platform(libs.koin.bom))
    implementation(libs.koin.core)
    implementation(libs.koin.ktor)
    implementation(libs.koin.loggerSlf4j)

    // The saga engine and its Postgres storage. petich-postgres brings exposed-core, -jdbc and
    // -json with it as `api`, and they are named again below because this module's own tables use
    // them — a transitive dependency that a compile-time reference relies on is a dependency waiting
    // to disappear when the intermediate stops needing it.
    // petich publishes no BOM, unlike kompot, so each coordinate carries the version — from one
    // catalogue entry, which is the same guarantee by a longer route.
    implementation(libs.petich.core)
    implementation(libs.petich.postgres)

    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.json)
    // Test-only: the schema check asks Exposed what DDL the migrated database still needs.
    testImplementation(libs.exposed.migrationJdbc)

    // The driver, the pool and the migrator: petich-postgres deliberately ships none of the three,
    // because it takes an Exposed Database and does not know which DBMS is underneath.
    implementation(libs.postgresql)
    implementation(libs.hikari)
    implementation(libs.flyway.core)
    // A separate coordinate since Flyway 10. Without it the Postgres dialect is simply absent and
    // the failure names the JDBC URL rather than the missing module.
    implementation(libs.flyway.postgresql)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    runtimeOnly(libs.logback.classic)

    testImplementation(libs.ktor.server.testHost)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.testcontainers.core)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
}
