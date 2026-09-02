package io.konekt.screens

import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.material3.M3Colors
import io.github.youndie.kompot.material3.M3Typography
import io.github.youndie.kompot.standard.ButtonComponent
import io.github.youndie.kompot.standard.ColumnComponent
import io.github.youndie.kompot.standard.RowComponent
import io.github.youndie.kompot.standard.TextComponent
import io.konekt.components.ButtonEmphasis
import io.konekt.components.SurfaceComponent
import io.konekt.components.SurfaceTones
import io.konekt.feature.esim.server.domain.EsimHoldings
import io.konekt.feature.shell.shared.api.SignOutAction

// THE ACCOUNT, as its owner sees it — and deliberately shorter than the canvas draws it.
//
// Section 05 fixes six rows: payment methods, auto top-up, my eSIMs, appearance, language, support.
// Four of those name features this product does not have. A row reading "Payment methods · 2 cards"
// on a build with no payment methods is a mockup wearing a product's clothes: it is the kind of
// screen that gets photographed, shown to somebody, and believed. So this draws what konekt knows —
// the number the session belongs to, what is installed on it, and the way out — and the absent rows
// stay absent rather than becoming decoration.
//
// THE TARIFF IS GONE FROM HERE, and that is `B-102` rather than tidying. It named a tariff nobody
// chose, beside a `Change tariff` control, and the catalogue behind it advertised `$5 / month` and
// `2 GB` — a price nothing in this build ever takes and an allowance nothing ever grants. A screen
// that offers a commitment the product does not have is the same defect as a row reading
// "Payment methods · 2 cards" on a build with no payment methods, one layer deeper: it survives a
// press.
//
// The saga is not gone. It is petich's second shape of transaction — suspend, confirm, a boundary in
// time — and it is demonstrated over `/api/v1/tariff-changes` by `TariffChangeScenarioTest`, which is
// where it always did its work. What went is the fiction on top of it.
//
// `Appearance` is the interesting omission of the four. It is not a missing feature, it is a CLIENT
// setting: which palette to draw is decided where the drawing happens, and a server-driven row for
// it would be this product's one piece of state that the server holds and cannot act on.
object ProfileScreen {
    // ONE VIEW AND A BAR, and that is the whole signature — `B-96`'s rule, and this is the vertical
    // it was written against. What it used to take was four values out of three repositories, and
    // one of them was a finished English sentence that `ProfileRouting` composed: a routing file,
    // whose job is to know who is calling, deciding what a subscriber reads.
    fun build(
        view: ProfileView,
        nav: KompotComponent? = null,
    ): KompotComponent =
        ColumnComponent(
            id = "profile",
            spacing = 12,
            children =
                buildList {
                    add(
                        TextComponent(
                            id = "profile-title",
                            text = "Profile",
                            style = M3Typography.HeadlineSmall,
                            color = M3Colors.OnSurface,
                        ),
                    )

                    // THE HEADER IS THE NUMBER (`B-114`, block 4). The canvas draws an avatar, a name
                    // and the number under it; there is no name and no initials to take (`B-55`), so
                    // the honest header is the number as the title with its label above — set as a
                    // header, not as a caption over a body-sized figure.
                    add(
                        TextComponent(
                            id = "profile-msisdn-label",
                            text = "Your number",
                            style = M3Typography.LabelMedium,
                            color = M3Colors.OnSurfaceVariant,
                        ),
                    )
                    add(
                        TextComponent(
                            id = "profile-msisdn",
                            text = "+${view.msisdn}",
                            style = M3Typography.HeadlineMedium,
                            color = M3Colors.OnSurface,
                        ),
                    )

                    // WHAT THE LINE HOLDS, as the settings list the canvas draws: a white card, a
                    // label on the left, the value on the right. One row today; the card is the shape
                    // the next row goes into.
                    add(
                        SurfaceComponent(
                            id = "profile-line",
                            dividers = true,
                            spacing = 12,
                            children =
                                listOf(
                                    RowComponent(
                                        id = "profile-esims-row",
                                        spacing = 12,
                                        children =
                                            listOf(
                                                TextComponent(
                                                    id = "profile-esims-label",
                                                    text = "My eSIMs",
                                                    style = M3Typography.TitleSmall,
                                                    color = M3Colors.OnSurface,
                                                    modifiers = TAKES_THE_SPACE,
                                                ),
                                                TextComponent(
                                                    id = "profile-esims",
                                                    text = esimLine(view.esims),
                                                    style = M3Typography.BodyMedium,
                                                    color = M3Colors.OnSurfaceVariant,
                                                ),
                                            ),
                                    ),
                                ),
                        ),
                    )

                    // SUPPORT AS THE MINT CARD with a heading — the canvas's, without its `Open chat`:
                    // there is no chat, and a button to nowhere is worse than no button.
                    add(
                        SurfaceComponent(
                            id = "profile-support",
                            tone = SurfaceTones.ACCENT,
                            spacing = 6,
                            children =
                                listOf(
                                    TextComponent(
                                        id = "profile-support-title",
                                        text = "Need a hand with an eSIM?",
                                        style = M3Typography.TitleMedium,
                                        color = M3Colors.OnPrimaryContainer,
                                    ),
                                    TextComponent(
                                        id = "profile-support-text",
                                        text = "Support answers around the clock. Have your order reference ready.",
                                        style = M3Typography.BodyMedium,
                                        color = M3Colors.OnPrimaryContainer,
                                    ),
                                ),
                        ),
                    )

                    // THE WAY OUT IS A RED ROW at the foot of the list, not the most prominent control
                    // on the screen — which a full-width primary pill for leaving was.
                    add(
                        SurfaceComponent(
                            id = "profile-leave",
                            spacing = 0,
                            children =
                                listOf(
                                    ButtonComponent(
                                        id = "profile-sign-out",
                                        text = "Sign out",
                                        action = SignOutAction(),
                                        variant = ButtonEmphasis.DANGER,
                                    ),
                                ),
                        ),
                    )
                    nav?.let(::add)
                },
        )

    private fun esimLine(esims: EsimHoldings): String =
        when {
            esims.held == 0 -> "No eSIM on this line yet"

            esims.awaitingInstall == 0 -> "${count(esims.installed)} installed"

            // NOT "ready to install". A profile still being prepared is in this bucket too, and it is
            // not ready for anything yet; what is true of both is that neither is on a device.
            esims.installed == 0 -> "${count(esims.awaitingInstall)} not installed yet"

            else -> "${count(esims.installed)} installed, ${esims.awaitingInstall} not installed yet"
        }

    private fun count(n: Int): String = if (n == 1) "1 eSIM" else "$n eSIMs"
}
