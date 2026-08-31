package io.konekt.client.render

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.youndie.kompot.KompotActionHandler
import io.github.youndie.kompot.KompotComponentRenderer
import io.github.youndie.kompot.LocalKompotDesignSystem
import io.github.youndie.kompot.form.FormController
import io.github.youndie.kompot.form.standard.EntityValue
import io.github.youndie.kompot.material3.M3Colors
import io.github.youndie.kompot.material3.M3Typography
import io.konekt.components.SliderInputComponent

// THE SLIDER, drawn from the steps the server priced.
//
// IT SNAPS AND CANNOT BE MOVED BETWEEN THEM, which is the whole reason `steps` travels rather than a
// min and a max. The server refuses a quantity that is not on its list, so a control able to express
// one would be a control whose every intermediate position is a refusal — and the subscriber would
// meet it as a failed purchase rather than as a stop.
//
// Compose's `Slider` snaps for us when it is given `steps`: the count is the number of positions
// BETWEEN the ends, so a six-value list is five gaps and four inner stops. Getting that off by one
// puts a stop where no price exists.
class SliderInputRenderer : KompotComponentRenderer<SliderInputComponent> {
    @Composable
    override fun Render(
        component: SliderInputComponent,
        actionHandler: KompotActionHandler,
        formController: FormController,
    ) {
        val design = LocalKompotDesignSystem.current

        // THE VALUE COMES FROM THE CONTROLLER AND NOT FROM LOCAL STATE. A slider holding its own
        // position would drift from the form the moment a patch, a reset or a validation moved the
        // field — and it is the controller the submit reads.
        val state by remember(component.fieldId) {
            formController.getFieldFlow<EntityValue>(component.fieldId)
        }.collectAsState(initial = null)

        val chosen = state?.value?.id
        val index = component.steps.indexOf(chosen).let { if (it >= 0) it else 0 }

        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = component.label,
                    style = design.resolveTypography(M3Typography.LabelMedium),
                    color = design.resolveColor(M3Colors.OnSurfaceVariant),
                )
                Text(
                    // The number and its unit, beside the label rather than under the track: it is
                    // the value the subscriber is choosing, and a figure below a slider reads as a
                    // caption about it.
                    text = listOfNotNull(component.steps.getOrNull(index), component.unit).joinToString(" "),
                    style = design.resolveTypography(M3Typography.TitleMedium),
                    color = design.resolveColor(M3Colors.OnSurface),
                )
            }

            // FEWER THAN TWO STEPS IS NOT A SLIDER. A track with one stop cannot be moved, and drawing
            // one would look like a control that is broken rather than a value that is fixed.
            if (component.steps.size >= 2) {
                Slider(
                    value = index.toFloat(),
                    onValueChange = { moved ->
                        val to = moved.toInt().coerceIn(0, component.steps.lastIndex)
                        val step = component.steps[to]
                        if (step != chosen) {
                            // BOTH CALLS, IN THIS ORDER. `onValueChanged` moves the field;
                            // `requestPatchIfNeeded` is what asks the server to reprice, and the
                            // toolkit does not infer the second from the first — a field that
                            // `triggersPatch` still has to say when it has settled.
                            formController.onValueChanged(component.fieldId, EntityValue(id = step, title = step))
                            formController.requestPatchIfNeeded(component.fieldId)
                        }
                    },
                    valueRange = 0f..component.steps.lastIndex.toFloat(),
                    // The stops BETWEEN the ends, which is what Compose counts. Six values is four.
                    steps = (component.steps.size - 2).coerceAtLeast(0),
                    colors =
                        SliderDefaults.colors(
                            thumbColor = design.resolveColor(M3Colors.Primary),
                            activeTrackColor = design.resolveColor(M3Colors.Primary),
                        ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
