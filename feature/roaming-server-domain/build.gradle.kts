plugins {
    id("konekt.jvm")
    `java-test-fixtures`
}

dependencies {
    // The domain sees the shared vocabulary and nothing else — no Exposed, so it cannot accidentally
    // depend on it, which is the entire reason the repository interface exists.
    api(project(":shared:domain"))
    implementation(libs.kotlinx.coroutines.core)

    // The in-memory repository, shared with every module that composes a saga in a test. One copy,
    // because three hand-rolled fakes are three chances for one of them to be dormant-by-default and
    // the others not.
    testFixturesApi(project(":shared:domain"))
    testFixturesImplementation(libs.kotlinx.coroutines.core)
}
