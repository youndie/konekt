package io.konekt.screens

import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.material3.M3Colors
import io.github.youndie.kompot.material3.M3Typography
import io.github.youndie.kompot.standard.ButtonComponent
import io.github.youndie.kompot.standard.ColumnComponent
import io.github.youndie.kompot.standard.NavigateAction
import io.github.youndie.kompot.standard.TextComponent
import io.konekt.components.BannerComponent
import io.konekt.components.MessageTones
import io.konekt.feature.esim.server.domain.EsimHoldings
import io.konekt.feature.shell.shared.api.SignOutAction
import io.konekt.feature.tariff.shared.api.TARIFFS_DEEPLINK
import io.konekt.tariff.waitingSentence

// THE ACCOUNT, as its owner sees it — and deliberately shorter than the canvas draws it.
//
// Section 05 fixes six rows: payment methods, auto top-up, my eSIMs, appearance, language, support.
// Four of those name features this product does not have. A row reading "Payment methods · 2 cards"
// on a build with no payment methods is a mockup wearing a product's clothes: it is the kind of
// screen that gets photographed, shown to somebody, and believed. So this draws what konekt knows —
// the number the session belongs to, what is installed on it, and the way out — and the absent rows
// stay absent rather than becoming decoration.
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
                    add(
                        TextComponent(
                            id = "profile-msisdn-label",
                            text = "Number",
                            style = M3Typography.LabelMedium,
                            color = M3Colors.OnSurfaceVariant,
                        ),
                    )
                    add(
                        TextComponent(
                            id = "profile-msisdn",
                            // WITH THE PLUS PUT BACK ON. `Msisdn` stores digits and nothing else,
                            // which is right for a key and wrong for a screen: "79990000777" reads
                            // as a local number in one country and as a wrong one in every other.
                            // The plus is what makes it E.164 to a reader.
                            //
                            // GROUPED IS WHAT THE CANVAS DRAWS — "+7 999 120-45-67" — and it is not
                            // done here on purpose. Grouping is per country, konekt normalises
                            // without keeping one, and a formatter that guessed would be wrong for
                            // exactly the subscribers a white-label product is sold to. Formatting
                            // belongs on this side (D15); this one needs a fact the domain does not
                            // carry yet.
                            text = "+${view.msisdn}",
                            style = M3Typography.TitleMedium,
                            color = M3Colors.OnSurface,
                        ),
                    )
                    add(
                        TextComponent(
                            id = "profile-esims",
                            text = esimLine(view.esims),
                            style = M3Typography.BodyMedium,
                            color = M3Colors.OnSurfaceVariant,
                        ),
                    )
                    add(
                        TextComponent(
                            id = "profile-tariff-label",
                            text = "Tariff",
                            style = M3Typography.LabelMedium,
                            color = M3Colors.OnSurfaceVariant,
                        ),
                    )
                    add(
                        TextComponent(
                            id = "profile-tariff",
                            text = view.tariffTitle,
                            style = M3Typography.TitleMedium,
                            color = M3Colors.OnSurface,
                        ),
                    )
                    // THE SENTENCE IS THE TARIFF CATALOGUE'S, not a second copy of it. Both screens
                    // tell a subscriber about the same waiting change, and this one used to spell it
                    // out again — in the routing file, which is where `B-96` found it.
                    //
                    // No control here, unlike the catalogue's banner: the way back to a confirmation
                    // is what the catalogue is for, and a second door to it on the profile is a
                    // second thing to keep in step for no gain.
                    view.pendingChange?.let {
                        add(
                            BannerComponent(
                                id = "profile-tariff-pending",
                                text = it.waitingSentence(),
                                tone = MessageTones.INFO,
                            ),
                        )
                    }
                    add(
                        ButtonComponent(
                            id = "profile-tariff-change",
                            text = "Change tariff",
                            // A `navigate` and not an action: the catalogue is an address known in
                            // advance, and what a press here does is move. Buying is the other shape
                            // — there the destination does not exist until the press.
                            action = NavigateAction(TARIFFS_DEEPLINK),
                            modifiers = FILLS_THE_ROW,
                        ),
                    )
                    add(
                        BannerComponent(
                            id = "profile-support",
                            text = "Support answers around the clock. Have your order reference ready.",
                            tone = MessageTones.INFO,
                        ),
                    )
                    add(
                        ButtonComponent(
                            id = "profile-sign-out",
                            text = "Sign out",
                            // An ACTION and not a `navigate`: a session has to be given up on both
                            // sides, and where the subscriber goes afterwards depends on that having
                            // worked. A `navigate` to the login screen would leave a live session
                            // behind a screen that says there is none.
                            action = SignOutAction(),
                            modifiers = FILLS_THE_ROW,
                        ),
                    )
                    // THE SHELL, added last and hoisted by the client out of the tree it arrived
                    // in. It is in the tree rather than fetched separately so that the SERVER decides
                    // which tab is current — it is the only side that knows which screen it just
                    // built, and a client comparing its address against an action's payload would be
                    // a second opinion that disagrees the first time an address gains a query
                    // parameter.
                    nav?.let(::add)
                },
        )

    // WHAT IS ON THIS LINE, and the word has to match the number.
    //
    // It said "installed" over a count of profiles HELD — a figure that exists for the device's slot
    // limit and says nothing about whether anything was ever scanned. So a subscriber who had bought
    // a profile and not installed it read "1 eSIM installed", on the one screen that exists to tell
    // them what they have (`B-69`).
    //
    // Both numbers when both are non-zero, rather than a total: "2 eSIMs" would be true and would
    // hide the one fact worth acting on. Singular and plural composed here like every other string on
    // the wire — "1 eSIMs" is the sort of thing a reader stops trusting a product over.
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
