package io.konekt.client.render

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import io.github.youndie.kompot.KompotActionHandler
import io.github.youndie.kompot.KompotComponentRenderer
import io.github.youndie.kompot.form.FormController
import io.github.youndie.kompot.kompotStandardRenderers
import io.github.youndie.kompot.standard.ColumnComponent
import io.github.youndie.kompot.standard.RowComponent

// WHO DECIDES HOW AN UNKNOWN COMPONENT IS DRAWN, and until now the answer was nobody.
//
// `UnknownBlockRenderer` chooses between a LINE and a CARD by reading `LocalUnknownBlockDensity`, and
// its own comment says the density is "chosen by where the block sits". Nothing chose. The local was
// declared, read once by that renderer, and provided by exactly one caller — the renderer's own test,
// which supplied the condition it was testing. So the CARD branch was unreachable from any screen:
// the `written-but-never-called` shape applied to a decision rather than to a function, and it stayed
// invisible until a real screen tried to produce it.
//
// THE CONTAINER DECIDES, and the alternative was the screen holder. The holder knows the SCREEN and
// not the neighbourhood, so a mixed screen would get one answer for all of it — and "where the block
// sits" is a fact about its neighbours, which is exactly what a container is. A block standing in a
// column is a card; one among a row of items is a line.
//
// The toolkit's own renderer does the drawing. These two only provide the local and delegate, because
// a copy of `ColumnRenderer` would be a second layout to keep in step with kompot's for one line of
// context — and the day they diverged, a column would lay out differently depending on which of the
// two the registry happened to hold.
class ColumnDensityRenderer(
    private val toolkit: KompotComponentRenderer<ColumnComponent> = standard(),
) : KompotComponentRenderer<ColumnComponent> {
    @Composable
    override fun Render(
        component: ColumnComponent,
        actionHandler: KompotActionHandler,
        formController: FormController,
    ) {
        CompositionLocalProvider(LocalUnknownBlockDensity provides UnknownBlockDensity.CARD) {
            toolkit.Render(component, actionHandler, formController)
        }
    }

    private companion object {
        @Suppress("UNCHECKED_CAST")
        fun standard(): KompotComponentRenderer<ColumnComponent> =
            kompotStandardRenderers[ColumnComponent::class] as KompotComponentRenderer<ColumnComponent>
    }
}

class RowDensityRenderer(
    private val toolkit: KompotComponentRenderer<RowComponent> = standard(),
) : KompotComponentRenderer<RowComponent> {
    @Composable
    override fun Render(
        component: RowComponent,
        actionHandler: KompotActionHandler,
        formController: FormController,
    ) {
        // A row is a line of things beside each other, and a card among them would break the line —
        // which is the whole reason the two densities exist rather than one.
        CompositionLocalProvider(LocalUnknownBlockDensity provides UnknownBlockDensity.LINE) {
            toolkit.Render(component, actionHandler, formController)
        }
    }

    private companion object {
        @Suppress("UNCHECKED_CAST")
        fun standard(): KompotComponentRenderer<RowComponent> =
            kompotStandardRenderers[RowComponent::class] as KompotComponentRenderer<RowComponent>
    }
}
