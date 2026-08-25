package io.konekt.feature.purchase.server.data

import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.decodeKompotComponent
import io.github.youndie.kompot.encodeKompotComponent
import io.github.youndie.kompot.generated.generatedKonektSerializersModule
import io.github.youndie.kompot.generated.generatedStandardSerializersModule
import io.github.youndie.kompot.kompotCoreSerializersModule
import io.github.youndie.kompot.standard.ColumnComponent
import io.github.youndie.kompot.standard.TextComponent
import io.github.youndie.kompot.standard.kompotStandardSerializersModule
import io.konekt.components.BannerComponent
import io.konekt.components.MessageTones
import io.konekt.components.OrderRowComponent
import io.konekt.components.OrderStatuses
import io.konekt.domain.Currency
import io.konekt.domain.Money
import io.konekt.feature.purchase.server.domain.OrderStatus
import io.konekt.feature.purchase.server.domain.OrderView
import io.konekt.feature.purchase.server.domain.PurchasePayload
import io.konekt.feature.purchase.server.domain.Reversal
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
