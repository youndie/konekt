package io.konekt.screens

import io.github.youndie.kompot.KompotComponent
import io.konekt.components.UsageCounterCardComponent
import io.konekt.components.konektWalk
import io.konekt.feature.esim.server.domain.EsimHoldings
import io.konekt.feature.usage.server.data.StaticUsageAddOns
import io.konekt.feature.usage.server.data.UsageCounterCards
import io.konekt.feature.usage.server.domain.UsageCounter
import io.konekt.roaming.RoamingPackageCards
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

// A LIVE UPDATE MUST SEND THE NODE THE SCREEN WOULD HAVE SENT, and nothing said so until an SSE
// update was watched breaking the screen it was updating.
//
// The allowance block groups the three counters into one card (`B-105`), so a counter is a ROW there
// rather than a card of its own. The shape was a parameter with a default, the screen passed the
// non-default and the live-update path took the default — so the instant a counter moved, SSE
// replaced a row with a full card: its own ground, its own corners, its own inset, nested inside the
// card it was living in. Correct until it changed, which is the worst kind of correct.
//
// The parameter is gone, which is the fix. This is the rule underneath it, because the next thing
// added to a card will be a parameter too: **whatever builds a node for a screen is the only thing
// that builds its replacement.** A push that differs from the screen in ANY field is a screen that
// rearranges itself while somebody is looking at it.
class LiveUpdateSendsTheSameNodeTest {
    private val at = Instant.fromEpochMilliseconds(1_700_000_000_000)
    private val cards = UsageCounterCards(StaticUsageAddOns())

    private fun counter(
        kind: UsageCounter.Kind,
        limit: Long,
        remaining: Long,
    ) = UsageCounter(
        subscriberId = "sub-1",
        kind = kind,
        limitUnits = limit,
        remainingUnits = remaining,
        startedAt = at,
    )

    private fun homeWith(counters: List<UsageCounter>): KompotComponent =
        HomeScreen(cards, RoamingPackageCards()).build(
            HomeView(
                at = at,
                brandName = "konekt",
                msisdn = "15550000001",
                balance = null,
                counters = counters,
                packages = emptyList(),
                esims = EsimHoldings(held = 1, awaitingInstall = 0, installed = 1),
            ),
        )

    @Test
    fun `the card an update pushes is the card the screen drew`() {
        // EVERY KIND AND EVERY STATE THE SCREEN CAN HOLD, because the divergence was in one field of
        // one shape and a single counter would have found it by luck.
        val counters =
            listOf(
                counter(UsageCounter.Kind.DATA, 20_480, 18_000),
                counter(UsageCounter.Kind.MINUTES, 300, 12),
                counter(UsageCounter.Kind.MESSAGES, 200, 0),
            )

        val onScreen =
            homeWith(counters)
                .konektWalk()
                .filterIsInstance<UsageCounterCardComponent>()
                .associateBy { it.id }

        // VACUITY FIRST: a walk that finds no counter card would make every comparison below a
        // statement about an empty map, and this walk crosses a `surface` the counters were moved
        // into by `B-105` — exactly the kind of nesting a walk stops at when it is written wrong.
        assertEquals(
            counters.size,
            onScreen.size,
            "the home screen did not draw one card per counter, so this test compares nothing",
        )

        counters.forEach { counter ->
            // What the live-update path sends for the same counter, built the same way
            // `UsageConsumer` builds it.
            val pushed = cards.of(counter, at)

            assertEquals(
                onScreen[pushed.id],
                pushed,
                "the update for ${counter.kind} differs from what the screen drew, so an SSE message " +
                    "rearranges the allowance card instead of updating it",
            )
        }
    }

    // AND THE SAME QUESTION FOR THE TRAVEL CARDS, which share the component and are built by a
    // different factory. They already agree — the home screen and the consumer make the identical
    // call — and this says so rather than leaving the other half of the pair unasserted.
    @Test
    fun `a travel card is built one way by both paths`() {
        val roaming = RoamingPackageCards()
        assertTrue(
            roaming::class.members.none { it.name == "of" && it.parameters.size > 3 },
            "`RoamingPackageCards.of` grew an argument beyond the package and the instant; if the " +
                "screen and the live update can now pass different ones, they will",
        )
    }
}
