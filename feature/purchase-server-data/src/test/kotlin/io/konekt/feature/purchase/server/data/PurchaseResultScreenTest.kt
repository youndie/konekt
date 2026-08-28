package io.konekt.feature.purchase.server.data

import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.decodeKompotComponent
import io.github.youndie.kompot.encodeKompotComponent
import io.github.youndie.kompot.generated.generatedKonektSerializersModule
import io.github.youndie.kompot.generated.generatedStandardSerializersModule
import io.github.youndie.kompot.kompotCoreSerializersModule
import io.github.youndie.kompot.standard.ButtonComponent
import io.github.youndie.kompot.standard.ColumnComponent
import io.github.youndie.kompot.standard.NavigateAction
import io.github.youndie.kompot.standard.TextComponent
import io.github.youndie.kompot.standard.kompotStandardSerializersModule
import io.konekt.components.BannerComponent
import io.konekt.components.ButtonEmphasis
import io.konekt.components.MessageTones
import io.konekt.components.OrderRowComponent
import io.konekt.components.OrderStatuses
import io.konekt.components.konektWalk
import io.konekt.domain.Currency
import io.konekt.domain.Money
import io.konekt.feature.purchase.server.domain.OrderStatus
import io.konekt.feature.purchase.server.domain.OrderView
import io.konekt.feature.purchase.server.domain.PurchasePayload
import io.konekt.feature.purchase.server.domain.PurchaseRefusals
import io.konekt.feature.purchase.server.domain.Reversal
import io.konekt.feature.purchase.shared.api.ConfirmPurchaseAction
import io.konekt.feature.purchase.shared.api.PLANS_DEEPLINK
import io.konekt.feature.purchase.shared.api.TOP_UP_DEEPLINK
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

// The rollback screen, asserted on the tree rather than on a screenshot.
//
// What it is really checking is the copy: the canvas is specific that a rollback is stated in money —
// what was reversed, what the balance is now, and the reference to quote — and every one of those is
// a sentence somebody could later "simplify" into an apology.
class PurchaseResultScreenTest {
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

    private val payload =
        PurchasePayload(
            subscriberId = "sub-1",
            accountId = "acc-1",
            planId = "tr-10gb-30d",
            planTitle = "Turkey · 10 GB · 30 days",
            price = Money.ofMajor(12, Currency.DEFAULT),
        )

    private val reversedOn = Instant.fromEpochMilliseconds(1_719_532_800_000) // 28 June

    private fun rejected(declineReason: String?) =
        OrderView(
            orderId = "8f214c90-1111-2222-3333-444455556666",
            status = OrderStatus.REJECTED,
            payload = payload,
            requiredAction = null,
            declineReason = declineReason,
        )

    private fun compensated(declineReason: String?) =
        OrderView(
            orderId = "8f214c90-1111-2222-3333-444455556666",
            status = OrderStatus.COMPENSATED,
            payload = payload,
            requiredAction = null,
            declineReason = declineReason,
        )

    private fun awaiting() =
        OrderView(
            orderId = "8f214c90-1111-2222-3333-444455556666",
            status = OrderStatus.AWAITING_CONFIRMATION,
            payload = payload,
            requiredAction = "CONFIRM",
            declineReason = null,
        )

    // THE BRANCH NOBODY ASSERTED ON, and it is the one the confirmation button was built for. Its
    // copy could be rewritten wholesale and this file stayed green — which is how it was found: a
    // rewrite passed, and a rewrite passing is the same evidence as a mutation surviving.
    // THE WAY OUT IS THE ANSWER OR THE OTHER OPTION, never both — over every state, because the one
    // that got it wrong is not the one that will get it wrong next.
    //
    // `Install eSIM` and `Done` were both filled primaries of the same width and colour, so the
    // completed purchase asked one question twice (`B-71`). Four of the five states had it right, by
    // each branch choosing correctly — which is exactly the arrangement that produced the fifth.
    @Test
    fun `no state draws two controls of equal weight`() {
        val states =
            listOf(
                OrderStatus.COMPLETED,
                OrderStatus.COMPENSATED,
                OrderStatus.REJECTED,
                OrderStatus.AWAITING_CONFIRMATION,
                OrderStatus.PENDING,
                OrderStatus.COMPENSATING,
            )
        assertEquals(OrderStatus.entries.size, states.size, "OrderStatus gained a value and this list did not")

        var withASecondControl = 0

        states.forEach { status ->
            val buttons =
                PurchaseResultScreen
                    .build(
                        order = compensated(PurchaseRefusals.INSUFFICIENT_FUNDS).copy(status = status),
                        reversal = Reversal(Money.ofMajor(12, Currency.DEFAULT), reversedOn),
                        balance = Money.ofMajor(3, Currency.DEFAULT),
                    ).konektWalk()
                    .filterIsInstance<ButtonComponent>()

            val primaries = buttons.filter { it.variant != ButtonEmphasis.QUIET }
            assertEquals(
                1,
                primaries.size,
                "$status draws ${primaries.size} controls of full weight: ${primaries.map { it.text }}",
            )
            if (buttons.size > 1) withASecondControl += 1
        }

        // VACUITY. Every state having exactly one button would satisfy the assertion above while
        // saying nothing about the rule, which is only about screens that have two.
        assertTrue(
            withASecondControl > 0,
            "no state drew a second control, so the rule under test was never exercised",
        )
    }

    // AND THE FULL-WEIGHT ONE IS THE ACTION, not the exit. A screen that demoted both, or demoted the
    // wrong one, satisfies "exactly one primary" — this says which.
    @Test
    fun `where there is something to do, the way out is the quiet one`() {
        val screen =
            PurchaseResultScreen.build(
                order = compensated(null).copy(status = OrderStatus.COMPLETED),
                reversal = null,
                balance = Money.ofMajor(3, Currency.DEFAULT),
            )
        val buttons = screen.konektWalk().filterIsInstance<ButtonComponent>()

        assertEquals(ButtonEmphasis.QUIET, buttons.single { it.id == "purchase-done" }.variant)
        assertTrue(
            buttons.single { it.id == "purchase-install" }.variant != ButtonEmphasis.QUIET,
            "the thing the subscriber came here to do is drawn as the afterthought",
        )
    }

    // EVERY REFUSAL SAYS WHICH ONE IT WAS, and until `B-68` all five said the same sentence.
    //
    // Asserted over the whole set rather than over the money branch that prompted the work, and by
    // COMPARING the sentences rather than matching each: a build that regressed to one constant would
    // satisfy any number of individual "contains" assertions written one at a time.
    @Test
    fun `the five refusals do not all read the same`() {
        val codes =
            listOf(
                PurchaseRefusals.INSUFFICIENT_FUNDS,
                PurchaseRefusals.NOT_ON_SALE,
                PurchaseRefusals.PRICE_CHANGED,
                PurchaseRefusals.NO_SUCH_PLAN,
                PurchaseRefusals.NO_ACCOUNT,
            )

        val sentences =
            codes.associateWith { code ->
                PurchaseResultScreen
                    .build(rejected(code), reversal = null, balance = Money.ofMajor(3, Currency.DEFAULT))
                    .konektWalk()
                    .filterIsInstance<BannerComponent>()
                    .single()
                    .text
            }

        assertEquals(
            codes.size,
            sentences.values.toSet().size,
            "refusals that should read differently do not: $sentences",
        )

        // `NO_ACCOUNT` keeps the sentence all five used to share, and shares it with a refusal that
        // was never recorded — deliberately, because there is nothing truthful to add about either.
        val generic = "This purchase could not be started, and nothing was charged."
        assertEquals(
            generic,
            sentences[PurchaseRefusals.NO_ACCOUNT],
            "a refusal with nothing to say invented something",
        )
        assertEquals(
            generic,
            PurchaseResultScreen
                .build(rejected(declineReason = null), reversal = null, balance = null)
                .konektWalk()
                .filterIsInstance<BannerComponent>()
                .single()
                .text,
            "an unrecorded refusal claimed to know which one it was",
        )
        // And none of them contradicts what happened: nothing was held on any of these.
        sentences.forEach { (code, text) ->
            assertTrue(
                "nothing was charged" in text.lowercase(),
                "$code does not say that nothing was charged: $text",
            )
        }
    }

    // THE ONE A SUBSCRIBER CAN ACT ON. `KonektException.InsufficientFunds` is its own case with the
    // stated reason that the screen offers a top-up; it did not, and this is what says so.
    @Test
    fun `being short of money names both numbers and offers the way to fix it`() {
        val screen =
            PurchaseResultScreen.build(
                rejected(PurchaseRefusals.INSUFFICIENT_FUNDS),
                reversal = null,
                balance = Money.ofMajor(3, Currency.DEFAULT),
            )

        val banner = screen.konektWalk().filterIsInstance<BannerComponent>().single()
        assertTrue("$12" in banner.text, "the price is not on the screen: ${banner.text}")
        // The BALANCE too, because "you do not have enough" is a sentence somebody has to do
        // arithmetic on before they know what to type into the top-up field.
        assertTrue("$3" in banner.text, "the balance is not on the screen: ${banner.text}")

        val buttons = screen.konektWalk().filterIsInstance<ButtonComponent>()
        val topUp = buttons.singleOrNull { it.id == "purchase-rejected-top-up" }
        assertNotNull(topUp, "no way to add money: ${buttons.map { it.text }}")
        assertEquals(NavigateAction(TOP_UP_DEEPLINK), topUp.action)

        // And it is the ANSWER, so the way out beside it is not drawn as one.
        val back = buttons.single { it.id == "purchase-rejected-back" }
        assertEquals(ButtonEmphasis.QUIET, back.variant, "two primaries is a screen asking one question twice")
    }

    // A BALANCE THAT COULD NOT BE READ IS NOT DRAWN AS ZERO, here as everywhere else. Zero would tell
    // a subscriber they have nothing when they may have plenty, on the one screen where what they
    // have is the whole question.
    @Test
    fun `being short of money with no readable balance still says the price`() {
        val banner =
            PurchaseResultScreen
                .build(rejected(PurchaseRefusals.INSUFFICIENT_FUNDS), reversal = null, balance = null)
                .konektWalk()
                .filterIsInstance<BannerComponent>()
                .single()

        assertTrue("$12" in banner.text, "the price went missing with the balance: ${banner.text}")
        assertTrue("$0" !in banner.text, "a balance that could not be read was drawn as zero: ${banner.text}")
    }

    // A CONTROL ONLY WHERE THERE IS SOMETHING TO PRESS. `Top up` on a plan that left the catalogue is
    // a button that changes nothing, which is worse than no button.
    @Test
    fun `a refusal about the catalogue sends the subscriber to the catalogue, not to the top-up`() {
        listOf(PurchaseRefusals.NOT_ON_SALE, PurchaseRefusals.PRICE_CHANGED, PurchaseRefusals.NO_SUCH_PLAN)
            .forEach { code ->
                val buttons =
                    PurchaseResultScreen
                        .build(rejected(code), reversal = null, balance = Money.ofMajor(50, Currency.DEFAULT))
                        .konektWalk()
                        .filterIsInstance<ButtonComponent>()

                assertEquals(
                    NavigateAction(PLANS_DEEPLINK),
                    buttons.single { it.id == "purchase-rejected-plans" }.action,
                )
                assertTrue(
                    buttons.none { it.id == "purchase-rejected-top-up" },
                    "$code offered a top-up, which would change nothing",
                )
            }
    }

    @Test
    fun `the confirmation says what, how much, and out of what`() {
        val texts =
            PurchaseResultScreen
                .build(awaiting(), reversal = null, balance = Money.ofMajor(50, Currency.DEFAULT))
                .konektWalk()
                .filterIsInstance<TextComponent>()
                .map { it.text }

        assertTrue("Turkey · 10 GB · 30 days" in texts, "the confirmation did not name the plan: $texts")
        assertTrue("$12" in texts, "the confirmation did not name the price: $texts")
        // THE SOURCE, which is the one thing a confirmation is for and the one the banner it replaced
        // never said. A subscriber agreeing to spend must be able to see what it comes out of.
        // "LEFT AFTER THIS", because the balance handed to this screen is already net of the hold —
        // `hold` decrements the account. A bare "Balance · $50" beside a price of $12 invites the
        // subscriber to subtract twice, and the banner's "not charged" then contradicts it.
        assertTrue(
            "Balance — $50 left after this" in texts,
            "the confirmation did not say where the money comes from, or said it ambiguously: $texts",
        )
        // The banner, read as a `banner` rather than swept up with the texts: it is konekt's own
        // component and carries its own field, and a `filterIsInstance<TextComponent>` walks straight
        // past it. That is how this assertion failed the first time it was written.
        val banner =
            PurchaseResultScreen
                .build(awaiting(), reversal = null, balance = Money.ofMajor(50, Currency.DEFAULT))
                .konektWalk()
                .filterIsInstance<BannerComponent>()
                .single()
        assertTrue(
            "on hold and has not been charged" in banner.text,
            "the screen claimed nothing had happened, beside a balance that has already moved: ${banner.text}",
        )
    }

    @Test
    fun `the control a subscriber presses carries the amount`() {
        val button =
            PurchaseResultScreen
                .build(awaiting(), reversal = null, balance = Money.ofMajor(50, Currency.DEFAULT))
                .konektWalk()
                .filterIsInstance<ButtonComponent>()
                .single { it.id == "purchase-confirm" }

        assertEquals("Pay $12", button.text, "somebody who reads only the control still has to read the price")
        assertEquals(ConfirmPurchaseAction("8f214c90-1111-2222-3333-444455556666"), button.action)
    }

    // A BALANCE THE SERVER COULD NOT READ IS LEFT OUT, not drawn as zero — the same rule the home
    // screen follows about the same number. Zero is a fact about an account and "we could not tell"
    // is not, and a subscriber who reads the first when the second is true thinks they cannot afford
    // something they can.
    @Test
    fun `a balance that could not be read is not invented`() {
        val texts =
            PurchaseResultScreen
                .build(awaiting(), reversal = null, balance = null)
                .konektWalk()
                .filterIsInstance<TextComponent>()
                .map { it.text }

        assertTrue(
            texts.none { it.startsWith("Balance") },
            "a balance was drawn for an account it could not read: $texts",
        )
        // The control control: the rest of the screen is still there, so the assertion above is about
        // one omitted row rather than about a screen that failed to build.
        assertTrue("$12" in texts, "the price went missing with the balance: $texts")
    }

    // EVERY STATE HAS A WAY OFF THE SCREEN, and one of them did not.
    //
    // The purchase result carries no tab bar — it is not a tab — so a state with nothing to press is
    // a subscriber stuck on it. Five of the six had an exit and the ROLLBACK did not: the one state
    // this product exists to demonstrate, and the one somebody arrives at with a question. Asserted
    // over the whole enum rather than for the branch that was missing, because the next branch added
    // is the one that will be missing next.
    @Test
    fun `no state of this screen is a dead end`() {
        val states =
            listOf(
                OrderStatus.COMPLETED,
                OrderStatus.COMPENSATED,
                OrderStatus.REJECTED,
                OrderStatus.AWAITING_CONFIRMATION,
                OrderStatus.PENDING,
                OrderStatus.COMPENSATING,
            )

        // Vacuity first: an enum that gained a value would leave this list short, and a list that is
        // short passes by not looking at the state it forgot.
        assertEquals(
            OrderStatus.entries.size,
            states.size,
            "OrderStatus gained a value and this list did not — the state added is the one unchecked",
        )

        states.forEach { status ->
            val screen =
                PurchaseResultScreen.build(
                    order = compensated(null).copy(status = status),
                    reversal = Reversal(Money.ofMajor(12, Currency.DEFAULT), reversedOn),
                    balance = Money.ofMajor(38, Currency.DEFAULT),
                )
            val buttons = screen.konektWalk().filterIsInstance<ButtonComponent>()

            assertTrue(buttons.isNotEmpty(), "$status draws no control at all — a subscriber lands here and is stuck")
        }
    }

    @Test
    fun `a reversal is stated in money, with a date and a reference`() {
        val screen =
            PurchaseResultScreen.build(
                order = compensated("The provider declined the operation."),
                reversal = Reversal(Money.ofMajor(12, Currency.DEFAULT), reversedOn),
                balance = Money.ofMajor(38, Currency.DEFAULT),
            )

        val banner = screen.find<BannerComponent>()
        assertEquals("The provider declined the operation.", banner.text)
        assertEquals(MessageTones.ERROR, banner.tone)

        val row = screen.find<OrderRowComponent>()
        assertEquals(OrderStatuses.COMPENSATED, row.status)
        // Short enough to read aloud to support, which is why it is on the screen at all.
        assertEquals("8f214c90", row.reference)
        assertEquals("28 Jun", row.dateText)
        assertEquals("$12 returned to balance on 28 Jun — nothing was activated.", row.noteText)

        // Stated as a current fact and NOT as "back to where it was": between the reversal and this
        // render the balance may have moved for an unrelated reason, and that sentence would then be
        // false while every number on the screen was true.
        val balance = screen.find<TextComponent>()
        assertEquals("Your balance is now $38.", balance.text)
        assertTrue("back to where" !in balance.text)
    }

    @Test
    fun `a purchase nobody confirmed has no provider to quote and does not invent one`() {
        val screen =
            PurchaseResultScreen.build(
                order = compensated(declineReason = null),
                reversal = Reversal(Money.ofMajor(12, Currency.DEFAULT), reversedOn),
                balance = Money.ofMajor(38, Currency.DEFAULT),
            )

        assertEquals(
            "The confirmation window passed, so the purchase was not completed.",
            screen.find<BannerComponent>().text,
        )
    }

    @Test
    fun `the amount comes from the ledger and not from the price`() {
        // They agree today. The ledger is the record of what happened and the price is the record of
        // what was asked for, so this asserts which one the screen reads — the only moment it can be
        // asserted at all, because a partial reversal does not exist yet.
        val screen =
            PurchaseResultScreen.build(
                order = compensated(null),
                reversal = Reversal(Money.ofMajor(7, Currency.DEFAULT), reversedOn),
                balance = Money.ofMajor(43, Currency.DEFAULT),
            )

        assertEquals(
            "$7 returned to balance on 28 Jun — nothing was activated.",
            screen.find<OrderRowComponent>().noteText,
        )
    }

    @Test
    fun `a rejected purchase says nothing was charged, because nothing was`() {
        val screen =
            PurchaseResultScreen.build(
                order = compensated(null).copy(status = OrderStatus.REJECTED),
                reversal = null,
                balance = Money.ofMajor(50, Currency.DEFAULT),
            )

        assertEquals(
            "This purchase could not be started, and nothing was charged.",
            screen.find<BannerComponent>().text,
        )
        assertTrue(screen.findAll<OrderRowComponent>().isEmpty(), "a rejected purchase drew a reversal row")
    }

    @Test
    fun `the whole screen survives the wire`() {
        // The tree is only worth anything if it reaches a client. This is the same check the
        // dictionary's own tests make, applied to a real screen: every type in it is registered, and
        // the ROOT keeps its discriminator.
        val screen =
            PurchaseResultScreen.build(
                order = compensated("declined"),
                reversal = Reversal(Money.ofMajor(12, Currency.DEFAULT), reversedOn),
                balance = Money.ofMajor(38, Currency.DEFAULT),
            )

        assertEquals(screen, json.decodeKompotComponent(json.encodeKompotComponent(screen)))
    }

    private inline fun <reified T : KompotComponent> KompotComponent.find(): T =
        findAll<T>().firstOrNull() ?: error("no ${T::class.simpleName} on the screen")

    private inline fun <reified T : KompotComponent> KompotComponent.findAll(): List<T> =
        when (this) {
            is ColumnComponent -> children.filterIsInstance<T>()
            else -> listOfNotNull(this as? T)
        }
}
