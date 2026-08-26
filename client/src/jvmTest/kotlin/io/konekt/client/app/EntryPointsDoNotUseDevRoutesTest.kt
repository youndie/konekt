package io.konekt.client.app

import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertTrue

// THE DEVELOPMENT ROUTE MUST NOT CREEP BACK INTO AN ENTRY POINT, and nothing would notice if it did.
//
// Both runners signed in through `/api/v1/dev/otp` for most of this build's life, and both said in
// their own comments what that is: a machine endpoint revealing any subscriber's code IS the
// authentication system. They did it because there was nowhere else to get a code, and `B-46` built
// the login screen so there is.
//
// What makes that stick is this test rather than the comment. Reaching for `DevOtp` again is one
// import and a green build — the route still exists, `DevRoutesAreNotProductionTest` only keeps it out
// of the production route TABLE, and a client calling it would be perfectly functional against a
// stand. The same source-reading idiom as `RunCatchingUsageTest` and `CallRespondUsageTest`, and for
// the same reason: what is being refused is a call site, not a behaviour.
class EntryPointsDoNotUseDevRoutesTest {
    private val entryPoints =
        listOf(
            Path.of("src", "jvmMain", "kotlin", "io", "konekt", "client", "Main.kt"),
            Path.of("src", "iosMain", "kotlin", "io", "konekt", "client", "ios", "HomeApp.kt"),
        )

    // The crash harness is deliberately NOT here: it has no session, signs in to nothing, and its
    // whole job is to throw. A list that grew to cover every file in `iosMain` would stop being about
    // entry points.
    @Test
    fun `no entry point reaches for a development route`() {
        entryPoints.forEach { path ->
            // COMMENTS ARE NOT CODE, and this guard read them on its own first run: `Main.kt` explains
            // that it USED to sign in through the code-readback route, and the sentence tripped the
            // test. That is worse than a false positive — the obvious way out is to reword the prose,
            // and a rule satisfied by rewording a sentence has stopped being about the code.
            // `ClockUsageTest` strips them for the same reason.
            val source = code(path.readText())

            assertTrue(
                "DevOtp" !in source,
                "$path signs in through the code-readback route — the login screen is the way in (B-46)",
            )
            assertTrue(
                "/api/v1/dev/" !in source,
                "$path names a development route by path, which no shipped client may do",
            )
        }
    }

    @Test
    fun `the entry points this test names exist`() {
        // The guard on the guard: a renamed runner leaves this test reading nothing and passing. It is
        // the same failure mode `ClockUsageTest` has an allowance check for.
        entryPoints.forEach { path ->
            assertTrue(path.toFile().exists(), "$path does not exist, so this test guards nothing")
        }
    }
}

// Line and block comments out, string literals left in: a path inside a string is a call about to
// happen, and a path inside a comment is a sentence about one that used to.
private fun code(text: String): String {
    val withoutBlocks = text.replace(BLOCK_COMMENT, "")
    return withoutBlocks.lines().joinToString("\n") { it.substringBefore("//") }
}

private val BLOCK_COMMENT = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
