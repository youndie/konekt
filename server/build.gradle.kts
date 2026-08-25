plugins {
    id("konekt.jvm")
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.exposedMigrations)
    application
    // The conformance declarations are shared by two consumers that cannot see each other's test
    // sources: :server's own coverage gate, which needs no stand, and :e2e's walk, which needs one.
    // A fixture rather than a copy — two copies of "what this deployment offers a conformance kit"
    // is exactly the drift the gate exists to catch.
    `java-test-fixtures`
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
    implementation(project(":shared:db"))
    implementation(project(":shared:server-common"))
    implementation(project(":shared:components"))
    // The feature vertical. :server composes features; a feature never sees :server.
    implementation(project(":feature:auth-server-data"))
    implementation(project(":feature:purchase-server-data"))
    implementation(project(":feature:esim-server-data"))
    implementation(project(":feature:realtime-shared-api"))
    implementation(project(":feature:usage-shared-api"))
    implementation(project(":feature:usage-server-data"))

    // One platform, and no kompot coordinate below names a version. See gradle/libs.versions.toml.
    implementation(platform(libs.kompot.bom))
    implementation(libs.kompot.core)
    implementation(libs.kompot.standard)
    // The token constants the screens name — M3Colors, M3Typography. The SERVER names a role
    // and the client resolves it, which is the whole point of a token: a brand kit repaints a
    // screen the server never saw.
    implementation(libs.kompot.dsMaterial)
    implementation(libs.kompot.ktor)
    implementation(libs.kompot.auth)
    implementation(libs.kompot.realtime)
    implementation(libs.kompot.realtimeServer)
    implementation(libs.ktor.server.sse)
    implementation(libs.ktor.server.resources)
    implementation(libs.ktor.server.auth)

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
    implementation(libs.petich.outboxCore)
    // The transport petich deliberately does not provide. JVM only — booblik-client is a
    // src/main/kotlin source set.
    implementation(libs.booblik.client)

    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.json)
    // Test-only: the schema check asks Exposed what DDL the migrated database still needs.
    testImplementation(libs.exposed.migrationJdbc)

    // petich-postgres deliberately ships no driver, pool or migrator, because it takes an Exposed
    // Database and does not know which DBMS is underneath. All three come from :shared:db.
    // The driver has to be on the RUNTIME classpath of whatever actually opens a connection, even
    // though the code that opens it lives in :shared:db.
    runtimeOnly(libs.postgresql)

    // THE OBSERVABILITY TRIO. All three rather than one plus stdout for the rest: what this build
    // demonstrates is one purchase visible in all three at once, and two of them make that half a
    // demonstration.
    implementation(libs.metrik.agent)
    implementation(libs.tracy.agent)
    implementation(libs.katcher.client)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    runtimeOnly(libs.logback.classic)

    testImplementation(libs.ktor.server.testHost)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.koin.test)
    testImplementation(libs.testcontainers.core)
    // The client SSE plugin lives in ktor-client-core — there is no ktor-client-sse artefact, which
    // is easy to assume from the server side, where ktor-server-sse IS one.
    testImplementation(libs.ktor.client.core)
    testImplementation(testFixtures(project(":shared:db")))
    testImplementation(libs.testcontainers.junit)

    // The conformance fixtures. `api` and not `implementation`: :e2e writes no address as a string
    // either, so it needs the same @Resource classes these declarations are keyed by.
    //
    // The fixtures see :server's main output automatically, which is where `io.konekt.openapi`
    // lives — the endpoint kinds and `endpointKey` are the vocabulary the declarations are written
    // in, and re-deriving them here would be the second spelling of the contract.
    testFixturesApi(project(":feature:auth-shared-api"))
    testFixturesApi(project(":feature:purchase-shared-api"))
    testFixturesApi(project(":feature:esim-shared-api"))
    testFixturesApi(project(":feature:realtime-shared-api"))
    testFixturesApi(project(":feature:usage-shared-api"))
    testFixturesApi(libs.kotlinx.serialization.json)
}

// ── the OpenAPI document ────────────────────────────────────────────────────────────────────────
//
// `docs/api/openapi.json` is generated from the routing tree and committed; `:server:test` compares
// the two on every run, so a hand-edit fails the build. This task is the recorder — the only thing
// that writes it — and it exists as a named Gradle task rather than only as a `make` target because
// B-23's acceptance is stated in terms of one.
//
// It is a `Test` task and not a `JavaExec`: the generator walks a routing tree, which means building
// the application, which is what the test source set already knows how to do.
//
// ON THE MAC. This repository is a one-way mutagen replica, so a file written on the Linux box is
// reverted by the next sync and the recording looks like it did nothing at all. `make openapi` is
// the wrapper that adds `LOCAL=1` to get past the hook that otherwise sends Gradle to WSL.
//
// Never up to date, deliberately: its output is a file outside the build directory that Gradle is
// not tracking, so an "UP-TO-DATE" here would mean "not recorded" while reading as success.
tasks.register<Test>("openApi") {
    group = "documentation"
    description = "Records docs/api/openapi.json from the routing tree. Run it as `make openapi`."

    val testTask = tasks.test.get()
    testClassesDirs = testTask.testClassesDirs
    classpath = testTask.classpath
    useJUnitPlatform()

    filter { includeTestsMatching("io.konekt.openapi.OpenApiDocumentTest") }
    environment("KONEKT_OPENAPI_RECORD", "true")
    outputs.upToDateWhen { false }
}
