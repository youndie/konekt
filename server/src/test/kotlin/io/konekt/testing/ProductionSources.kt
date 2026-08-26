package io.konekt.testing

import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.walk

// Every module's production Kotlin, found by walking the repository rather than by a list of roots.
//
// A list of roots is a maintenance burden that fails silently: the module added last is the one
// nobody adds, and the guard that scans it then passes by finding nothing. Walking finds whatever is
// there — and the guards using this each assert a floor on the file count, so an empty walk is a
// failure rather than a pass.
@OptIn(ExperimentalPathApi::class)
fun productionSources(): List<Path> {
    // The tests run with the module directory as the working directory; the repository root is its
    // parent.
    val root = Path("..")

    return root
        .walk()
        .filter { it.extension == "kt" }
        // `.rolling` IS A COPY OF THIS REPOSITORY AT ANOTHER COMMIT, extracted by
        // `scripts/rolling-check.sh` so an old server can be built. Left in the walk it doubles every
        // source guard's input: two `KonektClock.kt`, two `StatusPages.kt`, two of everything — and
        // the failures do not say so. `ClockUsageTest` reported "KonektClock.kt is allowed to read the
        // system clock but does not exist", because `singleOrNull` over two matches is null.
        //
        // It is inside the repository on purpose — the build machine sees this directory and nothing
        // else — so the exclusion belongs here rather than in the script's cleanup: a guard that is
        // correct only when a previous command tidied up is a guard with a precondition nobody states.
        .filter { path ->
            path.none { it.name == "build" || it.name == ".git" || it.name == "build-logic" || it.name == ".rolling" }
        }.filter { path ->
            val parts = path.map { it.name }
            // Production sources of any module and any Kotlin target: src/main/kotlin,
            // src/commonMain/kotlin, src/jvmMain/kotlin and whatever a platform adds later.
            val src = parts.indexOf("src")
            // Parenthesised deliberately: `a && b || c` binds as `(a && b) || c`, and without the
            // guard on `src` the `|| c` branch reads index 0 of every path — which is how a filter
            // ends up matching things it was never meant to and a guard ends up scanning the wrong
            // set.
            val sourceSet = if (src >= 0) parts.getOrNull(src + 1) else null
            sourceSet != null && (sourceSet.endsWith("Main") || sourceSet == "main")
        }.toList()
}
