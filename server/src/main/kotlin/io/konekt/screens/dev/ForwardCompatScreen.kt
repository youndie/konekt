package io.konekt.screens.dev

import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.KompotModifierNode
import io.github.youndie.kompot.standard.ColumnComponent
import io.github.youndie.kompot.standard.TextComponent
import io.konekt.components.CounterStates
import io.konekt.components.UsageCounterCardComponent
import io.ktor.resources.Resource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// A COMPONENT THE CLIENT CANNOT KNOW, and it lives HERE rather than in `:shared:components` for the
// one reason that makes it work at all.
//
// The dictionary module is generated from `@KompotComponentMarker`, and both sides read it — so a
// type declared there is a type the client registers, and it could never arrive unknown. Declaring it
// in the server's own source and registering it by hand in the server's `Json` is what makes it
// genuinely absent from the client's registry rather than pretend-absent.
//
// The name is the canvas's own example. The frame it draws has been in the design document since the
// beginning and was, until this route, a picture of a state the product could not enter.
@Serializable
@SerialName("esim_transfer_widget")
data class EsimTransferWidgetComponent(
    override val id: String,
    override val modifiers: List<KompotModifierNode> = emptyList(),
    val headline: String,
) : KompotComponent

// The development-only screen: one unknown component among known ones, twice.
//
// TWICE, AND THAT IS THE POINT. The replacement renderer has two densities — a LINE among rows and a
// CARD standing alone — and the density is chosen by where the block sits, which is a decision no
// unit test of the renderer can make for itself. Putting one of each in a real screen is the only
// way both are ever drawn by the code that chooses.
object ForwardCompatScreen {
    fun build(): KompotComponent =
        ColumnComponent(
            id = "forward-compat",
            spacing = 12,
            children =
                listOf(
                    TextComponent(
                        id = "forward-compat-title",
                        text = "This screen carries a component this client has never heard of.",
                    ),
                    // A known neighbour ABOVE and BELOW, because the claim being demonstrated is not
                    // "the block appears" but "everything around it still works". A screen containing
                    // only the unknown component would look identical whether the rest of the tree
                    // survived or not.
                    UsageCounterCardComponent(
                        id = "forward-compat-counter",
                        title = "Data",
                        valueText = "9.7 GB left",
                        state = CounterStates.NORMAL,
                        progress = 0.6f,
                    ),
                    EsimTransferWidgetComponent(
                        id = "forward-compat-line",
                        headline = "Transfer this eSIM to another device",
                    ),
                    UsageCounterCardComponent(
                        id = "forward-compat-counter-2",
                        title = "Minutes",
                        valueText = "120 min left",
                        state = CounterStates.LOW,
                        progress = 0.1f,
                    ),
                    EsimTransferWidgetComponent(
                        id = "forward-compat-card",
                        headline = "And again, standing on its own",
                    ),
                ),
        )
}

@Resource("/api/v1/dev/screens/forward-compat")
class ForwardCompatScreenResource
