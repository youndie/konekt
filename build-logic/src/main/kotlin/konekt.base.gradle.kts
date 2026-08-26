import org.jlleitschuh.gradle.ktlint.KtlintExtension

// What every module has regardless of its platform: a coordinate, a version, and one style.
//
// It lives in a convention plugin rather than in `subprojects { }` for two reasons. A module built
// on a lower toolchain than its dependencies fails with a message naming the DEPENDENCY, so the
// toolchain has to be impossible to forget; and a coordinate repeated per module is how six modules
// once got published under a group derived from a directory name.

plugins {
    id("org.jlleitschuh.gradle.ktlint")
}

group = "io.konekt"
version = providers.gradleProperty("VERSION").getOrElse("0.1.0-SNAPSHOT")

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

configure<KtlintExtension> {
    // The FORMATTER's version, pinned from the catalogue rather than left to the plugin's default.
    // Left to the default the style shifts whenever the plugin is bumped — which is precisely the
    // change nobody reads the diff of.
    version.set(libs.findVersion("ktlint").get().requiredVersion)
    // Generated sources are not ours to format, and a formatter that rewrites them makes the
    // generator's next run look like a change.
    filter {
        exclude { it.file.path.contains("/build/generated/") }
    }
}

// EVERY @Test THAT WAS DECLARED MUST HAVE BEEN EXECUTED, checked after every test run.
//
// JUnit 5 does not run a `@Test` method that returns something, and does not warn either: the method
// is simply not a test, and the class reports one fewer. Kotlin makes that easy to write by accident,
// because an expression-bodied test takes its return type from its last expression and
// `kotlin.test.assertNotNull` returns the value it checked. So a test ending in `assertNotNull(…)`
// compiles, is annotated, and never runs.
//
// THREE OF THEM WERE FOUND IN THIS REPOSITORY BY ACCIDENT, two of them months old. One covered
// `ExposedUsageCounters.consume`, whose subtract and clamp sat in the order that zeroes a
// subscriber's remaining allowance on any consumption taking more than half of it. The defect was
// covered. The cover had never executed once.
//
// WHAT IT COMPARES, AND WHY THAT IS NOT WHAT B-42 ASKED FOR. The item proposed reading the compiled
// classes and refusing a `@Test` whose descriptor does not end in `)V`, on the grounds that no regex
// over Kotlin can determine a return type. That is true, and this does not try to: it counts the
// `@Test` annotations a class DECLARES and compares that with the number of cases JUnit REPORTED for
// it. Strictly more is caught — a method ignored for any reason, a class not picked up at all — and
// nothing depends on which JVM the Gradle daemon happens to run on, which the Class-File API would.
//
// NOT COVERED: Kotlin/Native test tasks. `:client:iosSimulatorArm64Test` is a `KotlinNativeTest` and
// not a `Test`, so nothing below sees it. It is written down rather than left to be discovered.
tasks.withType<Test>().configureEach {
    val resultsDir = reports.junitXml.outputLocation
    val sourceRoot = layout.projectDirectory.dir("src").asFile
    val taskName = name

    doLast {
        // A FILTERED TASK IS RUNNING A SUBSET ON PURPOSE, so "declared and not run" is its job rather
        // than a defect. `:client:viddikVerify` is one: it is a `Test` task narrowed to the generated
        // screenshot fixtures, and every other class in the same source set is deliberately not its
        // business — the unfiltered `jvmTest` beside it covers them.
        //
        // Announced rather than skipped quietly. A check that silently declines to check is the same
        // shape of silence it exists to catch.
        // AN INCLUDE SELECTS AND AN EXCLUDE NARROWS, and treating them the same was a regression
        // that cost this check the whole of `:client:jvmTest`. A task with an include pattern is
        // deliberately running a subset, so there is nothing to compare; a task with an exclude runs
        // everything else, so it is still worth checking — with the excluded classes taken out of
        // what is expected rather than the check taken out of the build.
        val included = (this as Test).filter.includePatterns + DeclaredTests.commandLinePatterns(filter)
        if (included.isNotEmpty()) {
            logger.lifecycle("$taskName: filtered to ${included.joinToString()} — not checked for unrun tests")
            return@doLast
        }
        val excluded = filter.excludePatterns

        // READ AT EXECUTION TIME. Captured in the `configureEach` above it comes back empty: the
        // Kotlin plugin has not set the source set's output when this block is configured, and a
        // FileCollection captured then resolves to nothing rather than to the classes — a check that
        // silently sees no test classes and passes.
        val declared = DeclaredTests.declaredIn(sourceRoot, testClassesDirs, excluded)
        if (declared.isEmpty()) return@doLast

        val reported = DeclaredTests.reportedIn(resultsDir.get().asFile)

        // THE VACUITY GUARD, and it comes first. A module whose sources declare tests and whose
        // results directory is empty has proved nothing — and without this the comparison below would
        // pass, having found nothing to compare.
        check(reported.isNotEmpty()) {
            "$taskName: ${declared.size} test class(es) declare @Test and no results were written to " +
                "${resultsDir.get().asFile}. A run that executed nothing passes every comparison."
        }

        val shortfalls = DeclaredTests.shortfalls(declared, reported)
        check(shortfalls.isEmpty()) {
            "these declare tests that JUnit did not run — the commonest cause is an expression-bodied " +
                "@Test whose last expression returns a value, which makes the method non-void and " +
                "therefore not a test:\n  " + shortfalls.joinToString("\n  ")
        }

        logger.lifecycle("$taskName: every @Test in ${declared.size} class(es) was executed")
    }
}
