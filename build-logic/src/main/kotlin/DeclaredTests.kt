import java.io.File

// The comparison behind the "every declared test was executed" check, in a class of its own.
//
// Not in the convention script: a `doLast` that calls a top-level function of a `.gradle.kts` file
// captures the script object, and the configuration cache refuses to serialise one. The build fails
// with "cannot serialize Gradle script object references", which names the mechanism and not the
// mistake.
object DeclaredTests {
    // `@Test` on a line of its own, which is how this repository writes it and how ktlint keeps it.
    private val annotation = Regex("""^\s*@Test\s*$""", RegexOption.MULTILINE)

    private val header = Regex("""name="([^"]+)"\s+tests="(\d+)"""")

    // Only the classes THIS task compiled. A multiplatform module has one `src` tree and several test
    // tasks over it, so scanning sources alone would have `jvmTest` demand that an iosTest class
    // appear in its results — a failure with nothing wrong behind it.
    fun declaredIn(
        sourceRoot: File,
        testClassesDirs: Iterable<File>,
    ): Map<String, Int> {
        val compiled =
            testClassesDirs
                .filter { it.isDirectory }
                .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.map { it.name.removeSuffix(".class") } }
                .toSet()

        return sourceRoot
            .walkTopDown()
            .filter { it.isFile && it.name.endsWith("Test.kt") }
            .mapNotNull { file ->
                val simpleName = file.name.removeSuffix(".kt")
                if (simpleName !in compiled) return@mapNotNull null
                val count = annotation.findAll(file.readText()).count()
                if (count == 0) null else simpleName to count
            }.toMap()
    }

    fun reportedIn(resultsDir: File): Map<String, Int> {
        if (!resultsDir.isDirectory) return emptyMap()

        return resultsDir
            .listFiles { file -> file.name.endsWith(".xml") }
            .orEmpty()
            .mapNotNull { file ->
                header.find(file.readText(Charsets.UTF_8).take(600))?.let { it.groupValues[1] to it.groupValues[2].toInt() }
            }.toMap()
    }

    // Reported may legitimately EXCEED declared — a `@TestFactory` produces dynamic cases, and
    // viddik's generated fixture is one. Only a shortfall is a defect.
    // A multiplatform test task names its suite `MyTest[jvm]`, so the target has to come off before
    // the comparison. Without this the check reports every commonTest class in every multiplatform
    // module as never having run — a guard that cries wolf on a whole module is one that gets deleted.
    private fun simpleNameOf(reportedName: String): String = reportedName.substringAfterLast('.').substringBefore('[')

    fun shortfalls(
        declared: Map<String, Int>,
        reported: Map<String, Int>,
    ): List<String> =
        declared
            .mapNotNull { (simpleName, count) ->
                val ran = reported.entries.firstOrNull { simpleNameOf(it.key) == simpleName }
                when {
                    ran == null -> "$simpleName declares $count @Test and reported nothing at all"
                    ran.value < count -> "${ran.key} declares $count @Test and JUnit ran ${ran.value}"
                    else -> null
                }
            }.sorted()
}
