package io.konekt.screenshots

import androidx.compose.runtime.Composable
import io.github.youndie.kompot.KompotModifierNode
import io.github.youndie.kompot.SizeType
import io.konekt.components.CounterStates
import io.konekt.components.UsageCounterCardComponent
import ru.workinprogress.viddik.annotations.ViddikScreenshot

// The counter card in every state the wire can put it in — including one the wire can put it in and
// this build has never heard of.
//
// WHY THIS IS WORTH A GOLDEN AT ALL. The difference between these frames is COPY and a colour ROLE,
// not layout: `state` picks `M3Colors.Primary` / `Secondary` / `Error` and the server supplies the
// sentence. Copy is what regresses silently — nothing fails to compile when a caption stops being
// rendered, and no assertion on a node's existence notices a sentence that moved to a different line.
//
// THE FOURTH FRAME IS THE POINT OF THE OTHER THREE. `state` is an open string precisely so that a
// server one release ahead can name a state this client does not know, and the rule is that such a
// word draws the ORDINARY card — not nothing, not an error colour, and not an editorial guess. That
// rule lives in one `else` branch of `accentToken()`, and an `else` branch is exactly the kind of
// thing a refactor turns into `error("unknown state")` without any test objecting. So the unknown
// frame carries THE SAME DATA as the normal one, and `GoldenContentTest` asserts the two goldens are
// pixel-identical: a degradation that draws something of its own fails, and so does one that draws
// nothing.

private const val FRAME_WIDTH = 360
private const val FRAME_HEIGHT = 200

// Not one of `CounterStates`, and `ScreenshotCasesTest` keeps it that way. A negative fixture whose
// word quietly becomes a real one is a test that still passes and no longer tests anything.
const val UNKNOWN_COUNTER_STATE = "grace_period"

private fun counter(
    state: String,
    title: String,
    valueText: String,
    captionText: String?,
    progress: Float,
) = UsageCounterCardComponent(
    id = "counter-$state",
    modifiers = listOf(KompotModifierNode.Size(width = SizeType.Fill)),
    title = title,
    valueText = valueText,
    captionText = captionText,
    progress = progress,
    state = state,
)

// The ordinary state: a quota, a remainder, a bar, and deliberately no caption. The canvas fills the
// caption in only where there is something to say. Held as constants because the unknown-state frame
// below has to be able to reuse them EXACTLY.
private const val ORDINARY_TITLE = "Data"
private const val ORDINARY_VALUE = "15.8 GB left"
private const val ORDINARY_PROGRESS = 0.38f

@Composable
@ViddikScreenshot(name = "Normal", group = "Counter", width = FRAME_WIDTH, height = FRAME_HEIGHT)
fun CounterNormal() {
    BrandFrame(DEFAULT_BRAND) {
        Tree(
            listOf(
                counter(
                    state = CounterStates.NORMAL,
                    title = ORDINARY_TITLE,
                    valueText = ORDINARY_VALUE,
                    captionText = null,
                    progress = ORDINARY_PROGRESS,
                ),
            ),
        )
    }
}

@Composable
@ViddikScreenshot(name = "Low", group = "Counter", width = FRAME_WIDTH, height = FRAME_HEIGHT)
fun CounterLow() {
    BrandFrame(DEFAULT_BRAND) {
        Tree(
            listOf(
                counter(
                    state = CounterStates.LOW,
                    title = "Minutes",
                    valueText = "100 min left",
                    // The canvas's own copy for this frame: a projection and what it costs to fix.
                    // Pre-formatted on the server, money included — a client that cannot format
                    // cannot format inconsistently.
                    captionText = "Minutes run out in about two days at your pace. A 100-minute add-on costs \$1.49.",
                    progress = 0.9f,
                ),
            ),
        )
    }
}

@Composable
@ViddikScreenshot(name = "Exhausted", group = "Counter", width = FRAME_WIDTH, height = FRAME_HEIGHT)
fun CounterExhausted() {
    BrandFrame(DEFAULT_BRAND) {
        Tree(
            listOf(
                counter(
                    state = CounterStates.EXHAUSTED,
                    title = "Messages",
                    valueText = "0 left",
                    captionText = "You have used every message in this plan. Buy an add-on to keep sending.",
                    progress = 1f,
                ),
            ),
        )
    }
}

// THE SAME CARD AS `CounterNormal`, one word different on the wire. Nothing else may differ, or the
// identity assertion in `GoldenContentTest` stops being about the degradation.
@Composable
@ViddikScreenshot(name = "Unknown state", group = "Counter", width = FRAME_WIDTH, height = FRAME_HEIGHT)
fun CounterUnknownState() {
    BrandFrame(DEFAULT_BRAND) {
        Tree(
            listOf(
                counter(
                    state = UNKNOWN_COUNTER_STATE,
                    title = ORDINARY_TITLE,
                    valueText = ORDINARY_VALUE,
                    captionText = null,
                    progress = ORDINARY_PROGRESS,
                ),
            ),
        )
    }
}
