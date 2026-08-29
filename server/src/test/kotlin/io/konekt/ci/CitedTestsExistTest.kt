package io.konekt.ci

import io.konekt.testing.everyKotlinSource
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// A COMMENT NAMING A GUARD IS DOING THE GUARD'S JOB, and nothing checked that the guard was there.
//
// Three development routes and a client test each carried "… with `DevRoutesAreNotProductionTest`
// keeping it off a real build", and no such test existed (`B-84`). Writing it turned up two more of
// the same shape in the tree: `Shell.kt` cited a navigation test twice in the present tense after
// `B-49` deleted it, and two files named a topic test that has always been called something else.
//
// The class is cheap to create and invisible to every other check. A cited test that does not exist
// reads exactly like one that does — more convincingly, in fact, because four files agreeing on a
// name is most of the work of having the thing.
class CitedTestsExistTest {
    @Test
    fun `every test named in a comment is in the tree`() {
        val sources = everyKotlinSource()
        val existing = sources.map { it.nameWithoutExtension }.toSet()

        val dangling =
            sources
                .flatMap { file ->
                    CITATION
                        .findAll(file.readText())
                        .map { it.groupValues[1] }
                        .filterNot { it in existing }
                        .map { "${file.fileName} cites $it" }
                }.distinct()
                .sorted()

        assertEquals(emptyList(), dangling, "a comment names a test that is not in this repository: $dangling")
    }

    // VACUITY, and it is the whole risk here: a regex that matched nothing would pass the assertion
    // above on any tree at all, including one where every citation is a fiction. The floor is well
    // under what the tree carries — this guard exists to catch a missing test, not to be edited every
    // time somebody writes a comment.
    @Test
    fun `the scan actually finds citations`() {
        val cited =
            everyKotlinSource()
                .flatMap { file -> CITATION.findAll(file.readText()).map { it.groupValues[1] } }
                .toSet()

        assertTrue(
            cited.size >= 20,
            "the citation scan found only ${cited.size} names, so it is checking almost nothing",
        )
        assertTrue(
            "DevRoutesAreNotProductionTest" in cited,
            "the scan does not find the citation this guard was written for",
        )
    }

    private companion object {
        // A backticked name ending in `Test`, which is how this repository cites a test in prose. The
        // backticks are what keep it out of imports and code — a citation is something a person wrote
        // for another person to read.
        //
        // THE PATTERN IS BUILT, NOT SPELLED, so this file does not contain a citation of its own that
        // it would then have to be exempted from. A guard carrying an exemption for itself is one line
        // away from carrying one for the case it exists to catch.
        val CITATION = Regex("`([A-Z][A-Za-z0-9]*" + "Test)`")
    }
}
