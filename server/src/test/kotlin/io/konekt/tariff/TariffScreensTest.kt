package io.konekt.tariff

import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.standard.ButtonComponent
import io.github.youndie.kompot.standard.TextComponent
import io.konekt.components.BannerComponent
import io.konekt.components.PlanCardComponent
import io.konekt.components.konektWalk
import io.konekt.domain.Currency
import io.konekt.domain.Money
import io.konekt.feature.purchase.server.domain.OrderStatus
import io.konekt.feature.tariff.shared.api.ChangeTariffAction
import io.konekt.feature.tariff.shared.api.ConfirmTariffChangeAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

// THE SCREENS `B-21` NEVER GOT. The saga, the table, the confirmation and the routes were all built
// and their only caller was an end-to-end test — so what these assert is not the engine, which was
// already covered, but the two things a subscriber can only learn from a screen: which tariff they
// are on, and what is about to happen to it.
class TariffScreensTest {
    @Test
    fun `the current tariff is marked and cannot be pressed`() {
        val cards = cardsOf(TariffsScreen.build(TARIFFS, currentTariffId = "tr-standard", pending = null))

        // Every tariff is drawn, including the one they are on. A catalogue that hid the current one
        // would answer "what am I on" with silence.
        assertEquals(TARIFFS.map { "tariff-${it.id}" }, cards.map { it.id })

        val current = cards.single { it.id == "tariff-tr-standard" }
        assertEquals("Your tariff", current.badgeText, "the current tariff is not marked as current")
        assertNull(current.action, "the current tariff offers a change to itself")

        // And the others do. Without this the assertion above passes on a screen where nothing at all
        // is pressable, which is the state a pending change produces and a different case entirely.
        val others = cards.filterNot { it.id == "tariff-tr-standard" }
        assertEquals(
            listOf("tr-basic", "tr-max"),
            others.map { (it.action as ChangeTariffAction).tariffId },
            "the tariffs a subscriber could move to do not offer the move",
        )
    }

    // A CHANGE ALREADY WAITING TAKES EVERY OFFER OFF THE SCREEN, and puts the way back to it on top.
    //
    // The server refuses a second change while one is pending — 409 — so a card that accepted a press
    // here would be a control that is refused after being used. The client decides what is pressable
    // and the server decides what is offered, and neither may be the only one that knows (`B-68`).
    @Test
    fun `a pending change withdraws every offer and says where to go`() {
        val screen =
            TariffsScreen.build(
                TARIFFS,
                currentTariffId = "tr-basic",
                pending =
                    TariffChangeRecord(
                        changeId = "chg-1",
                        subscriberId = "sub-1",
                        fromTariffId = "tr-basic",
                        toTariffId = "tr-max",
                        status = TariffChangeStatuses.PENDING,
                        effectiveAt = FIRST_OF_A_MONTH,
                    ),
            )

        assertTrue(
            cardsOf(screen).all { it.action == null },
            "a tariff is still offered while a change is waiting, and the server would refuse it",
        )

        val banner = screen.konektWalk().filterIsInstance<BannerComponent>().single { it.id == "tariffs-pending" }
        assertTrue("Max" in banner.text, "the banner does not say which tariff the change is to: ${banner.text}")
        assertEquals(
            "chg-1",
            (banner.action as ConfirmTariffChangeAction).changeId,
            "the banner offers no way back to the change it is about",
        )
    }

    // THE CONFIRMATION IS THE ONLY CONTROL, AND ONLY WHILE ONE IS WANTED. A screen that kept the
    // button after the change was decided would offer to confirm something twice; one that never had
    // it would leave the saga suspended with no way to resume it — which is the state this whole
    // vertical was in before `B-86`, reachable only from a test.
    @Test
    fun `the confirmation appears exactly when the saga is waiting for it`() {
        val waiting = TariffChangeScreen.build(view(OrderStatus.AWAITING_CONFIRMATION, ACTION_CONFIRM_TARIFF), TARIFFS)
        val confirm = waiting.konektWalk().filterIsInstance<ButtonComponent>().single()
        assertEquals("chg-1", (confirm.action as ConfirmTariffChangeAction).changeId)

        listOf(OrderStatus.COMPLETED, OrderStatus.REJECTED, OrderStatus.COMPENSATED, OrderStatus.PENDING)
            .forEach { status ->
                assertTrue(
                    TariffChangeScreen
                        .build(view(status, null), TARIFFS)
                        .konektWalk()
                        .filterIsInstance<ButtonComponent>()
                        .isEmpty(),
                    "the change screen offers a confirmation in state $status, where nothing is waiting for one",
                )
            }
    }

    // FOUR ENDS AND FOUR SENTENCES, and the reason is `B-68`'s: five refusals that rendered as one
    // sentence naming none of them. A subscriber told only that something did not work cannot tell
    // whether trying again is worth anything.
    @Test
    fun `each outcome reads differently, and every one names both tariffs and the date`() {
        val states =
            listOf(
                ACTION_CONFIRM_TARIFF to OrderStatus.AWAITING_CONFIRMATION,
                null to OrderStatus.COMPLETED,
                null to OrderStatus.REJECTED,
                null to OrderStatus.COMPENSATED,
            )

        val screens = states.map { (action, status) -> TariffChangeScreen.build(view(status, action), TARIFFS) }

        val sentences =
            screens.map { screen ->
                screen
                    .konektWalk()
                    .filterIsInstance<BannerComponent>()
                    .single()
                    .text
            }
        assertEquals(
            sentences.size,
            sentences.toSet().size,
            "two outcomes of a tariff change read the same, so the screen does not say which happened: $sentences",
        )

        // AND EVERY ONE STILL ANSWERS THE QUESTION THE SCREEN IS FOR. Without this the assertion above
        // is satisfied by four different sentences over a screen that names neither tariff.
        screens.forEach { screen ->
            val texts = screen.konektWalk().filterIsInstance<TextComponent>().map { it.text }
            assertTrue("Basic" in texts, "the screen does not name the tariff they are on: $texts")
            assertTrue("Max" in texts, "the screen does not name the tariff they are changing to: $texts")
            assertTrue(texts.any { it.contains("Jan") }, "the screen does not say when it takes effect: $texts")
        }
    }

    private fun cardsOf(screen: KompotComponent): List<PlanCardComponent> =
        screen.konektWalk().filterIsInstance<PlanCardComponent>()

    private fun view(
        status: OrderStatus,
        requiredAction: String?,
    ) = TariffChangeView(
        changeId = "chg-1",
        status = status,
        currentTariffId = "tr-basic",
        requestedTariffId = "tr-max",
        effectiveAt = FIRST_OF_A_MONTH,
        requiredAction = requiredAction,
    )

    private companion object {
        // 1 January 2027, UTC — a date `DayFormat` writes as "1 Jan", which is what the assertions
        // above look for. A fixed instant rather than a clock: a screen test that reads the clock
        // passes on any date and asserts nothing about the one the server chose.
        val FIRST_OF_A_MONTH: Instant = Instant.fromEpochMilliseconds(1_798_761_600_000L)

        val TARIFFS =
            listOf(
                Tariff("tr-basic", "Basic", Money.ofMajor(5, Currency.DEFAULT), dataMb = 2_000),
                Tariff("tr-standard", "Standard", Money.ofMajor(12, Currency.DEFAULT), dataMb = 10_000),
                Tariff("tr-max", "Max", Money.ofMajor(25, Currency.DEFAULT), dataMb = 50_000),
            )
    }
}
