plugins {
    id("konekt.jvm")
}

dependencies {
    // api: Msisdn and the challenge stand in the signatures of the repository and the use cases, so
    // -server-data needs them on its compile classpath.
    api(project(":feature:auth-shared-api"))
    // Money, the refusals, suspendRunCatching and KonektClock. api rather than implementation: all
    // four stand in signatures this module publishes.
    api(project(":shared:domain"))

    implementation(libs.kotlinx.coroutines.core)

    // MockK resolves here because every target of this module is the JVM. It would not in a module
    // that also targets iOS — see research-stack §1.3 — which is one more reason the server domain is
    // kotlin("jvm") rather than multiplatform.
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
}
