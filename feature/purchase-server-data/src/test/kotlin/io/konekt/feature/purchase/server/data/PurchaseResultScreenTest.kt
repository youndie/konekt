package io.konekt.feature.purchase.server.data

import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.decodeKompotComponent
import io.github.youndie.kompot.encodeKompotComponent
import io.github.youndie.kompot.generated.generatedKonektSerializersModule
import io.github.youndie.kompot.generated.generatedStandardSerializersModule
import io.github.youndie.kompot.kompotCoreSerializersModule
import io.github.youndie.kompot.standard.ButtonComponent
import io.github.youndie.kompot.standard.ColumnComponent
import io.github.youndie.kompot.standard.TextComponent
import io.github.youndie.kompot.standard.kompotStandardSerializersModule
import io.konekt.components.BannerComponent
import io.konekt.components.MessageTones
import io.konekt.components.OrderRowComponent
import io.konekt.components.OrderStatuses
import io.konekt.components.konektWalk
import io.konekt.domain.Currency
import io.konekt.domain.Money
import io.konekt.feature.purchase.server.domain.OrderStatus
import io.konekt.feature.purchase.server.domain.OrderView
import io.konekt.feature.purchase.server.domain.PurchasePayload
import io.konekt.feature.purchase.server.domain.Reversal
import io.konekt.feature.purchase.shared.api.ConfirmPurchaseAction
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.plus
import kotlin.test.Test
import kotlin.test.assertEquals
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
