plugins {
    id("konekt.jvm")
    // The Postgres harness is shared by every module that touches a database — :server and each
    // feature's -server-data. Test fixtures rather than a copy per module: two harnesses are two
    // container lifecycles and two chances for one of them to be subtly different.
    `java-test-fixtures`
}

dependencies {
    // The Exposed declarations of the tables that belong to no single feature. kotlin("jvm") because
    // exposed-core publishes no common metadata (research-stack §1.2), which is also why this cannot
    // simply live in :shared:domain.
    api(libs.exposed.core)
    api(libs.exposed.jdbc)

    // The driver, the pool and the migrator live with the schema they apply, and the migrations
    // themselves are this module's resources — so any module that can open a database can also
    // migrate one, and a feature's tests do not have to depend on the thing that composes them.
    api(libs.hikari)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)

    // The migration-file gate reads the resources directory, so it needs the module root as its
    // working directory — stated rather than assumed, because a Gradle default that moved would make
    // it read an empty directory, which is why it also asserts it found something.
    testFixturesApi(libs.testcontainers.postgresql)
    testFixturesApi(libs.exposed.jdbc)
    testFixturesImplementation(libs.testcontainers.core)
    testFixturesImplementation(libs.postgresql)
    testFixturesImplementation(libs.hikari)
    testFixturesImplementation(libs.flyway.core)
    testFixturesImplementation(libs.flyway.postgresql)
}

tasks.withType<Test>().configureEach {
    workingDir = projectDir
}
