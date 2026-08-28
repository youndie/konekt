package io.konekt.screens

import io.github.youndie.kompot.standard.TextComponent
import io.konekt.components.konektWalk
import io.konekt.feature.esim.server.domain.EsimHoldings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// THE SCREEN THAT TELLS A SUBSCRIBER WHAT THEY HAVE, and nothing asserted on it at all until now.
//
// Which is how it came to say "1 eSIM installed" about a profile nobody had scanned. The number under
// that word was `countHeldBy` — a figure that exists for the device's eight-profile slot limit, whose
// own query comments that slots are "the whole content of this query" — and the parameter was even
// called `esimsHeld`. Only the sentence said installed (`B-69`).
//
// The model was never confused: there is an `INSTALLED` status, a `markInstalled`, and a comment on
// it insisting that "Installed is not active". The distinction was made everywhere except in the one
// place a person reads.
class ProfileScreenTest {
    private fun line(esims: EsimHoldings): String =
        ProfileScreen
            .build(msisdn = "+15551234567", esims = esims)
            .konektWalk()
            .filterIsInstance<TextComponent>()
            .single { it.id == "profile-esims" }
            .text

    // A TABLE OVER THE WHOLE SPACE rather than the one case that was wrong. What broke here was a
    // word that was right for one state and wrong for another, so the assertion has to be about the
    // states rather than about the word.
    @Test
    fun `the sentence matches what is actually on the line`() {
        assertEquals("No eSIM on this line yet", line(EsimHoldings.none))
        assertEquals("1 eSIM installed", line(EsimHoldings(held = 1, awaitingInstall = 0, installed = 1)))
        assertEquals("1 eSIM not installed yet", line(EsimHoldings(held = 1, awaitingInstall = 1, installed = 0)))
        assertEquals("2 eSIMs installed", line(EsimHoldings(held = 2, awaitingInstall = 0, installed = 2)))
        // BOTH NUMBERS when both are non-zero. "2 eSIMs installed" would be a true statement about the
        // total and would hide the one fact worth acting on.
        assertEquals(
            "1 eSIM installed, 1 not installed yet",
            line(EsimHoldings(held = 2, awaitingInstall = 1, installed = 1)),
        )
    }

    // THE DEFECT, STATED AS A PROPERTY. A line holding nothing installed must never say "installed",
    // whatever else changes about the copy — which is a claim the exact strings above do not make,
    // because a rewrite would move them together.
    @Test
    fun `a line with nothing on a device never claims something is installed`() {
        listOf(
            EsimHoldings.none,
            EsimHoldings(held = 1, awaitingInstall = 1, installed = 0),
            EsimHoldings(held = 3, awaitingInstall = 3, installed = 0),
        ).forEach { esims ->
            val text = line(esims)
            // The one phrase that legitimately contains the word is removed first, and then the word
            // must be gone. Plainer than a regex, and it says exactly what the claim is.
            assertTrue(
                "installed" !in text.replace("not installed", ""),
                "a line with nothing on a device says something is installed: $text",
            )
        }
    }

    // Singular and plural, because "1 eSIMs" is the sort of thing a reader stops trusting a product
    // over — and because the plural is the part a table of two cases would not have covered.
    @Test
    fun `the plural agrees with the number`() {
        assertTrue("1 eSIM " in line(EsimHoldings(held = 1, awaitingInstall = 0, installed = 1)))
        assertTrue("2 eSIMs " in line(EsimHoldings(held = 2, awaitingInstall = 0, installed = 2)))
    }
}
