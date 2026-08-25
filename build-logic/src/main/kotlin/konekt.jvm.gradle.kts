// A JVM module: the server, and every data layer.
//
// `kotlin("jvm")` rather than multiplatform-with-one-jvm-target, and that is not a style choice —
// exposed-core publishes no common metadata variant, so a multiplatform consumer meets resolution
// friction against it for nothing. See docs/research/research-stack.md §1.2.

plugins {
    id("konekt.base")
    id("org.jetbrains.kotlin.jvm")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
    jvmToolchain(libs.findVersion("jvmToolchain").get().requiredVersion.toInt())

    compilerOptions {
        // A warning nobody has to act on is a warning everybody learns to scroll past.
        allWarningsAsErrors.set(true)
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

tasks.withType<Test>().configureEach {
    // JUnit 5, because testcontainers-junit-jupiter and MockK both live there.
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

dependencies {
    "testImplementation"(kotlin("test"))
}
