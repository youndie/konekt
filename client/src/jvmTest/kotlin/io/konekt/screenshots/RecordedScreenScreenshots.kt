package io.konekt.screenshots

import androidx.compose.runtime.Composable
import io.github.youndie.kompot.KompotActionHandler
import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.LocalKompotRegistry
import io.github.youndie.kompot.decodeKompotComponent
import io.github.youndie.kompot.form.FormController
import io.github.youndie.kompot.form.FormSchema
import io.konekt.client.net.konektClientJson
import ru.workinprogress.viddik.annotations.ViddikScreenshot

// A GOLDEN OF A TREE THE SERVER PRODUCED, which is B-43's fourth acceptance criterion and the one
// thing every other frame in this package cannot be.
//
// The other goldens photograph components assembled in the test. That is right for what they are for —
// a counter card in four states is four sets of values, and no server produces all four on demand —
// but it means they cannot fail when the SERVER stops sending what they draw. A card that lost its
// caption on the wire, a field renamed, a colour token the screen no longer carries: every one of
// those leaves a hand-built fixture untouched and green.
//
// So this frame is decoded from a response recorded off the running stand: sign in, top up, buy the
// home plan, confirm, and read `/api/v1/screens/home`. What is committed is that answer verbatim.
//
// DECODED WITH THE CLIENT'S OWN `Json`, not a permissive one built here. If the recording contains a
// type this build cannot decode, the frame must show the degradation block rather than a fixture's
// idea of the screen — which is the same rule the running client follows and the reason this is worth
// photographing at all.
//
// Refreshing it is deliberate work, not a build step: a recording that regenerated itself would agree
// with whatever the server does today, and agreeing with today is precisely what a golden must not do.
private const val RECORDING = "/recorded/home-screen.json"

private const val FRAME_WIDTH = 360
private const val FRAME_HEIGHT = 260

private fun recordedHomeScreen(): KompotComponent {
    val json =
        RecordedScreenMarker::class.java
            .getResourceAsStream(RECORDING)
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: error("the recorded response is missing at $RECORDING — a golden of nothing is not a golden")

    return konektClientJson.decodeKompotComponent(json)
}

// A class to resolve the resource against, so the path is package-relative to nothing and absolute
// from the classpath root. Anonymous lookups through the thread's context loader are the version of
// this that works in a test and returns null inside a shadowed jar.
private class RecordedScreenMarker

@ViddikScreenshot(
    name = "Recorded home",
    group = "Screen",
    width = FRAME_WIDTH,
    height = FRAME_HEIGHT,
    darkVariant = true,
)
@Composable
fun RecordedHomeScreen() {
    BrandFrame(DEFAULT_BRAND) {
        val registry = LocalKompotRegistry.current
        // The ROOT the server sent, rendered as itself. Wrapping it in a column of ours would
        // photograph our frame around their screen and hide a root that arrived as something else.
        registry.RenderNode(
            component = recordedHomeScreen(),
            actionHandler = KompotActionHandler { },
            formController = FormController(FormSchema(formId = "recorded", fields = emptyList())),
        )
    }
}
