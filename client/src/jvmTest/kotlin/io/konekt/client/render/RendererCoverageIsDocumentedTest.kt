package io.konekt.client.render

import io.konekt.components.konektWireNames
import kotlinx.serialization.SerialName
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// A SENTENCE IN A DOCUMENT, HELD AGAINST THE CODE IT DESCRIBES.
//
// `design-app-canvas.md` said this client rendered "two of the nine types only" and gave that as the
// reason two sections of the canvas had no goldens. It was true when written and stopped being true
// three releases later, when `B-45` shipped six renderers — and the goldens for both sections arrived
// without anybody touching the prose beside them. Nothing could notice: a document is not compiled,
// `code_anchors` checks that a PATH still exists and cannot read a COUNT, and the claim was
// load-bearing rather than decorative.
//
// So the sentence names the types and this test reads it. What is asserted is agreement, not a
// number: a count would go stale the same way, one release later and one digit at a time.
class RendererCoverageIsDocumentedTest {
    @Test
    fun `the document names exactly the types this client can draw`() {
        val document = canvasDocument()

        // Vacuity first, and it is not ceremony: a missing file, a renamed heading or a rewritten
        // paragraph would leave `named` empty, and an empty set compared against an empty set is a
        // test that passes by finding nothing.
        val sentence =
            document
                .lines()
                .firstOrNull { it.startsWith("**Every type this build serves has a renderer**") }
                ?: error("the paragraph naming the rendered types is gone from design-app-canvas.md")

        val paragraph = document.substringAfter(sentence).substringBefore("\n\n")
        val named = Regex("`([a-z_]+)`").findAll(sentence + paragraph).map { it.groupValues[1] }.toSet()

        assertTrue(named.isNotEmpty(), "the paragraph names no types at all, so the comparison below is about nothing")

        assertEquals(
            // FROM THE MAP, not from a list this file keeps: a second list would be a second thing to
            // forget, which is the whole failure being guarded against.
            konektRenderers.keys
                .mapNotNull {
                    it.annotations
                        .filterIsInstance<SerialName>()
                        .singleOrNull()
                        ?.value
                }.filter { it in konektWireNames }
                .toSet(),
            named - NOT_A_COMPONENT,
            "the document and `konektRenderers` disagree about which types this client can draw",
        )
    }

    // Read from the repository root rather than from a resource, because the subject is the file a
    // person edits. Walking up rather than a relative path: the working directory of a test is the
    // module's, and this file lives two levels above it.
    private fun canvasDocument(): String {
        var directory = Path("").toAbsolutePath()
        repeat(6) {
            val candidate = directory.resolve("docs/design/design-app-canvas.md")
            if (candidate.exists()) return candidate.readText()
            directory = directory.parent ?: return@repeat
        }
        error("design-app-canvas.md was not found above ${Path("").toAbsolutePath()}")
    }

    private companion object {
        // Names the paragraph mentions that are not wire types: the test that keeps the two lists
        // apart, and the function it reads. Declared rather than filtered by shape, so a genuine type
        // cannot hide behind a pattern.
        val NOT_A_COMPONENT = setOf("RendererCoverageIsDocumentedTest", "konektRenderers")
    }
}
