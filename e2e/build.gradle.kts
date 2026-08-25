plugins {
    id("konekt.jvm")
    alias(libs.plugins.kotlinSerialization)
}

// THE STAND'S SUITE, and it has no `main`. Everything here is a test that drives a running deployment
// over HTTP; there is nothing to publish and nothing for another module to depend on.
dependencies {
    // The wire, so a path is never written as a string here either — the same @Resource classes the
    // server routes with.
    testImplementation(project(":feature:auth-shared-api"))
    testImplementation(project(":feature:purchase-shared-api"))
    testImplementation(project(":feature:usage-shared-api"))
    testImplementation(project(":feature:realtime-shared-api"))
    testImplementation(project(":feature:esim-shared-api"))
    testImplementation(project(":shared:domain"))
    testImplementation(project(":shared:components"))

    testImplementation(platform(libs.kompot.bom))
    testImplementation(libs.kompot.core)
    testImplementation(libs.kompot.standard)
    testImplementation(libs.kompot.auth)
    testImplementation(libs.kompot.realtime)

    // THE CONFORMANCE KIT, and it lives here rather than in :server:test because its subject is a
    // DEPLOYMENT. `assertTheWalkVisitedEveryTarget` asks what a run reached, and a run that reaches
    // an in-process object graph assembled by a test answers about that graph. The coverage gate
    // over the committed document stays in :server:test, where it needs no stand.
    testImplementation(libs.kompot.tck)
    // The declarations both gates read: which check claims which endpoint, and what this deployment
    // admits it cannot feed. One copy, so the two cannot drift.
    testImplementation(testFixtures(project(":server")))
    // The wire specification of this build — the schemas the kit validates every response against.
    testImplementation(project(":shared:spec"))

    testImplementation(libs.ktor.client.core)
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.ktor.client.resources)
    testImplementation(libs.ktor.client.contentNegotiation)
    testImplementation(libs.ktor.serialization.json)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.serialization.json)
    // THE STAND SEEDS A BALANCE DIRECTLY, because the product has no way to add one: a subscriber
    // is created with zero and nothing tops it up (B-40). Reaching into the stand's database is
    // honest for a stand — the alternative is inventing a development-only top-up endpoint so that a
    // test has something to call, which is a production surface added for a test.
    testImplementation(libs.postgresql)
    testRuntimeOnly(libs.logback.classic)
}

// NOT PART OF `check`, and that is deliberate rather than shy. This suite needs a stand that is
// already up; wired into `check` it would fail every ordinary build on a machine that has not started
// one, and a suite that fails for a reason unrelated to the change is a suite people learn to ignore.
//
// The named task is what CI runs and what a person runs, which is the same command in both places.
val e2e by tasks.registering(Test::class) {
    group = "verification"
    description = "Drives a running stand over HTTP. Bring it up first: make stand-up"
    testClassesDirs =
        sourceSets.test
            .get()
            .output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    // Never up to date: the stand is the input and Gradle cannot see it.
    outputs.upToDateWhen { false }
    systemProperty("konekt.stand.server", System.getenv("KONEKT_STAND_SERVER") ?: "http://127.0.0.1:8080")
    systemProperty("konekt.stand.declining", System.getenv("KONEKT_STAND_DECLINING") ?: "http://127.0.0.1:8081")
    // THE THREE COLLECTORS. Read back rather than trusted: an agent that is switched off produces
    // exactly the same silence as one that is working, so the only way to tell is to ask the far end
    // whether anything arrived.
    systemProperty("konekt.stand.metrik", System.getenv("KONEKT_STAND_METRIK") ?: "http://127.0.0.1:8090")
    systemProperty("konekt.stand.tracy", System.getenv("KONEKT_STAND_TRACY") ?: "http://127.0.0.1:8091")
    systemProperty("konekt.stand.katcher", System.getenv("KONEKT_STAND_KATCHER") ?: "http://127.0.0.1:8092")
    systemProperty(
        "konekt.stand.jdbc",
        System.getenv("KONEKT_STAND_JDBC") ?: "jdbc:postgresql://127.0.0.1:55432/konekt",
    )
    // The compose file's own default, so `make stand-up && ./gradlew :e2e:e2e` needs no environment.
    systemProperty("konekt.stand.compose", rootProject.file("deploy/compose.yaml").absolutePath)
}

tasks.named<Test>("test") {
    // The ordinary test task runs nothing here. Everything in this module needs the stand.
    enabled = false
}
