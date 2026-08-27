package io.konekt.screens

import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.material3.M3Colors
import io.github.youndie.kompot.material3.M3Typography
import io.github.youndie.kompot.standard.ButtonComponent
import io.github.youndie.kompot.standard.ColumnComponent
import io.github.youndie.kompot.standard.TextComponent
import io.konekt.components.BannerComponent
import io.konekt.components.MessageTones
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
// `Appearance` is the interesting omission of the four. It is not a missing feature, it is a CLIENT
// setting: which palette to draw is decided where the drawing happens, and a server-driven row for
// it would be this product's one piece of state that the server holds and cannot act on.
object ProfileScreen {
    fun build(
        msisdn: String,
        esimsHeld: Int,
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
                            text = "+$msisdn",
                            style = M3Typography.TitleMedium,
                            color = M3Colors.OnSurface,
                        ),
                    )
                    add(
                        TextComponent(
                            id = "profile-esims",
                            text = esimLine(esimsHeld),
                            style = M3Typography.BodyMedium,
                            color = M3Colors.OnSurfaceVariant,
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

    // Singular and plural, composed here like every other string on the wire. "1 eSIMs" is the sort
    // of thing a reader stops trusting a product over.
    private fun esimLine(held: Int): String =
        when (held) {
            0 -> "No eSIM installed on this line yet"
            1 -> "1 eSIM installed"
            else -> "$held eSIMs installed"
        }
}
