package io.konekt.screens

import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.decodeKompotComponent
import io.github.youndie.kompot.encodeKompotComponent
import io.github.youndie.kompot.generated.generatedKonektSerializersModule
import io.github.youndie.kompot.generated.generatedStandardSerializersModule
import io.github.youndie.kompot.kompotCoreSerializersModule
import io.github.youndie.kompot.standard.ColumnComponent
import io.github.youndie.kompot.standard.NavigateAction
import io.github.youndie.kompot.standard.TextComponent
import io.github.youndie.kompot.standard.kompotStandardSerializersModule
import io.konekt.components.BannerComponent
import io.konekt.components.CounterStates
import io.konekt.components.UsageCounterCardComponent
import io.konekt.domain.Currency
import io.konekt.domain.Money
import io.konekt.feature.usage.server.data.StaticUsageAddOns
import io.konekt.feature.usage.server.data.UsageCounterCards
import io.konekt.feature.usage.server.domain.UsageCounter
import io.konekt.time.KonektClock
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

// The first screen, asserted on the tree.
//
// It is the item that proves the loop — server builds, client renders, no layout on the client — so
// what this checks is the two things a screenshot could not: that the copy changes with the state,
// and that the tree survives the wire with its root discriminator intact.
class HomeScreenTest {
    private val start = Instant.fromEpochMilliseconds(1_700_000_000_000)
    private val now = start + 18.days

    private val cards = UsageCounterCards(StaticUsageAddOns(), KonektClock { now })

    private val json =
        Json {
            ignoreUnknownKeys = true
            classDiscriminator = "type"
            serializersModule =
                kompotCoreSerializersModule +
                kompotStandardSerializersModule +
                generatedStandardSerializersModule +
                generatedKonektSerializersModule
        }

    private fun counter(
        kind: UsageCounter.Kind,
        limit: Long,
        remaining: Long,
    ) = UsageCounter("sub-1", kind, limit, remaining, startedAt = start)

    @Test
    fun `the balance is stated and every counter gets a card`() {
        val screen =
            HomeScreen.build(
                balance = Money.ofMajor(38, Currency.DEFAULT),
                counters =
                    listOf(
                        counter(UsageCounter.Kind.DATA, 10_240, 4_000),
                        counter(UsageCounter.Kind.MINUTES, 1_000, 100),
                    ),
                cards = cards,
            )

        val texts = screen.all<TextComponent>().map { it.text }
        assertTrue("Balance" in texts, "no balance label: $texts")
        // Formatted on the server, in the product's currency, the American way.
        assertTrue("\$38" in texts, "no balance amount: $texts")

        val counterCards = screen.all<UsageCounterCardComponent>()
        assertEquals(listOf("counter-data", "counter-minutes"), counterCards.map { it.id })
    }

    @Test
    fun `the low state changes the copy and not only the colour`() {
        val screen =
            HomeScreen.build(
                balance = Money.ofMajor(38, Currency.DEFAULT),
                counters = listOf(counter(UsageCounter.Kind.MINUTES, 1_000, 100)),
                cards = cards,
            )

        val card = screen.all<UsageCounterCardComponent>().single()
        assertEquals(CounterStates.LOW, card.state)
        // The canvas's sentence, whole: when it runs out, and what it costs to fix. Both halves are
        // things a later edit could quietly drop.
        assertEquals(
            "Minutes run out in about two days at your current pace. A 100-minute add-on costs \$4.",
            card.captionText,
        )
    }

    @Test
    fun `a subscriber with no plan is told so and given somewhere to go`() {
        val screen = HomeScreen.build(Money.ofMajor(50, Currency.DEFAULT), counters = emptyList(), cards = cards)

        assertTrue(screen.all<UsageCounterCardComponent>().isEmpty())
        val banner = assertNotNull(screen.all<BannerComponent>().singleOrNull(), "an empty home drew nothing")
        assertEquals("No plan is active on this line yet.", banner.text)
        // A screen that draws nothing is indistinguishable from one that failed to load; a way out is
        // what makes it a state rather than a dead end.
        assertTrue(banner.action is NavigateAction)
    }

    @Test
    fun `a balance that could not be read is left out rather than drawn as zero`() {
        val screen = HomeScreen.build(balance = null, counters = emptyList(), cards = cards)

        val texts = screen.all<TextComponent>().map { it.text }
        assertTrue(texts.none { it == "Balance" }, "a balance block was drawn for a balance we do not have: $texts")
        // The failure this prevents: a subscriber reading "$0" tops up money they already have.
        assertTrue(texts.none { it.startsWith("$") }, texts.toString())
    }

    @Test
    fun `the whole screen survives the wire`() {
        val screen =
            HomeScreen.build(
                balance = Money.ofMajor(38, Currency.DEFAULT),
                counters = listOf(counter(UsageCounter.Kind.DATA, 10_240, 500)),
                cards = cards,
            )

        // The root keeps its discriminator and every type in the tree is registered. This is the same
        // check the dictionary makes, applied to a real screen — and the reason the route answers
        // with respondKompotComponent rather than call.respond.
        assertEquals(screen, json.decodeKompotComponent(json.encodeKompotComponent(screen)))
    }

    private inline fun <reified T : KompotComponent> KompotComponent.all(): List<T> =
        (listOf(this) + ((this as? ColumnComponent)?.children ?: emptyList())).filterIsInstance<T>()
}
