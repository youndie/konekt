package io.konekt.client.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.LocalKompotDesignSystem
import io.github.youndie.kompot.ds.material.KompotOverlays
import io.github.youndie.kompot.material3.M3Colors
import io.github.youndie.kompot.navigation.ScreenRoutePresentation
import io.konekt.client.theme.LocalKonektShapeScale
import kotlin.math.roundToInt

// THE LAYER OVER A SCREEN, drawn the canvas's way rather than Material's (`B-116`).
//
// kompot's own host draws a stock `ModalBottomSheet`, and section 03 of the canvas is not one: a
// scrim of the page's ink rather than Material's black, the brand's large radius on the two top
// corners (36 on brand A, where Material has 28), a 44 × 5 handle in the kit's outline colour, and a
// 20-point inset that matches every screen. So konekt draws its own and reads kompot's state for it —
// `KompotOverlays.presented`, public since kompot `B-66` for exactly this — and answers through the
// one call that closes a layer, `dismiss()`. It never writes the state itself: `withOverlays` in the
// holder's chain is what opens the layer and what closes it on a `navigate`, so this host and the
// toolkit cannot disagree about whether a sheet is open.
//
// WHAT CLOSES IT, and all three are the same `dismiss()`: a tap on the scrim, a drag down, and — from
// inside — any `navigate` (kompot `B-67`, in the chain) or an action answered with somewhere else to
// be (the holder closes it, see `KonektApp`). Dismissing sends nothing anywhere. For the confirmation
// that is the point: leaving it unconfirmed is the path where the order keeps its deadline and rolls
// itself back.
//
// NOT DRAWN: `asking`. konekt's server sends no `confirm`, and a question drawn by a host nobody
// exercises is a question nobody has seen. The day one is sent, it needs a frame of its own.
object KonektSheetHost {
    // What this client can draw over a screen, as `PresentationHeader.presentedAs` wants it. `dialog`
    // is absent on purpose: an answer asking for one is drawn as a screen rather than as a sheet it
    // did not ask for.
    val DRAWS: Set<String> = setOf(ScreenRoutePresentation.SCREEN, ScreenRoutePresentation.SHEET)

    // Named for the tests and for a screen reader, which is the same audience: both need to find
    // the control by what it does.
    const val SCRIM = "Close the sheet"
    const val HANDLE = "Drag down to close"
    const val SHEET_TAG = "konekt-sheet"
}

@Composable
internal fun KonektSheetHost(
    overlays: KompotOverlays,
    darkMode: Boolean,
    draw: @Composable (KompotComponent) -> Unit,
) {
    val layer = overlays.presented ?: return
    val designSystem = LocalKompotDesignSystem.current
    val shapes = LocalKonektShapeScale.current
    val density = LocalDensity.current

    // How far the sheet has been dragged down, and it starts at zero for every layer: a sheet that
    // opened where the previous one was let go of would open half closed.
    var dragged by remember(layer) { mutableFloatStateOf(0f) }
    val dismissAt = with(density) { DISMISS_DISTANCE.toPx() }

    Box(modifier = Modifier.fillMaxSize()) {
        // THE SCRIM, over the whole window, status bar included — the canvas dims the clock too. It
        // is the page's ink at the canvas's 42% in the light theme; the canvas draws no dark frame of
        // this, and ink over a dark page would LIGHTEN it, so the dark theme dims with the kit's
        // darkest colour instead, heavier because what is under it is already dark.
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        if (darkMode) {
                            designSystem.resolveColor(M3Colors.Background).copy(alpha = DARK_SCRIM_ALPHA)
                        } else {
                            designSystem.resolveColor(M3Colors.OnSurface).copy(alpha = LIGHT_SCRIM_ALPHA)
                        },
                    ).clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { overlays.dismiss() }
                    .semantics { contentDescription = KonektSheetHost.SCRIM },
        )

        Column(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .testTag(KonektSheetHost.SHEET_TAG)
                    .offset { IntOffset(0, dragged.roundToInt()) }
                    .clip(RoundedCornerShape(topStart = shapes.large, topEnd = shapes.large))
                    .background(designSystem.resolveColor(M3Colors.Surface))
                    // DOWN ONLY, and let go early it springs back: a sheet that follows the finger up
                    // past where it opened is a sheet that can be dragged off the top of the window.
                    .draggable(
                        state = rememberDraggableState { delta -> dragged = (dragged + delta).coerceAtLeast(0f) },
                        orientation = Orientation.Vertical,
                        onDragStopped = { velocity ->
                            if (dragged > dismissAt || velocity > DISMISS_VELOCITY) {
                                overlays.dismiss()
                            } else {
                                dragged = 0f
                            }
                        },
                    )
                    // The sheet reaches the bottom edge of the window and its content stays clear of
                    // the gesture bar — the same split the frame makes for the page.
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                    .padding(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .size(width = 44.dp, height = 5.dp)
                        .clip(CircleShape)
                        .background(designSystem.resolveColor(M3Colors.OutlineVariant))
                        .semantics { contentDescription = KonektSheetHost.HANDLE },
            )
            draw(layer.content)
        }
    }
}

// The canvas's `rgba(20,26,25,.42)` — its on-surface ink at 42%.
private const val LIGHT_SCRIM_ALPHA = 0.42f
private const val DARK_SCRIM_ALPHA = 0.72f

// A third of a short sheet, or a flick: either reads as "put it away".
private val DISMISS_DISTANCE = 120.dp
private const val DISMISS_VELOCITY = 1_500f
