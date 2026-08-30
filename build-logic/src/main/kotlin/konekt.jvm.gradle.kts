// A JVM module: the server, and every data layer.
//
// `kotlin("jvm")` rather than multiplatform-with-one-jvm-target, and that is not a style choice —
// exposed-core publishes no common metadata variant, so a multiplatform consumer meets resolution
// friction against it for nothing. See docs/research/research-stack.md §1.2.
//
// The toolchain, `allWarningsAsErrors`, the JUnit platform and `kotlin("test")` used to be spelled
// out here; they come from `ru.workinprogress.sborka.jvm`, with the numbers in `gradle.properties`.
// What is left below is what is konekt's.

plugins {
    id("konekt.base")
    id("org.jetbrains.kotlin.jvm")
    id("ru.workinprogress.sborka.jvm")
}

kotlin {
    compilerOptions {
        // Nullability annotations from Java libraries are believed rather than treated as unknown:
        // a platform type that comes back null is a crash at the call site with nothing in it about
        // the library that promised otherwise.
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

tasks.withType<Test>().configureEach {
    // Beside the conventions' own logging of failures: a skipped test is a test somebody has to
    // decide about, and in a build with testcontainers behind an assumption that decision is the
    // difference between "green" and "green because nothing ran".
    testLogging {
        events("skipped")
    }
}
