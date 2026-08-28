package io.konekt.client.render

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.youndie.kompot.KompotActionHandler
import io.github.youndie.kompot.KompotComponentRenderer
import io.github.youndie.kompot.KompotSurfaceRoles
import io.github.youndie.kompot.LocalKompotDesignSystem
import io.github.youndie.kompot.form.FormController
import io.github.youndie.kompot.material3.M3Colors
import io.github.youndie.kompot.material3.M3Typography
import io.konekt.components.EsimQrComponent
import qrcode.exception.InsufficientInformationDensityException
import qrcode.raw.ErrorCorrectionLevel
import qrcode.raw.QRCodeProcessor

// The activation code, drawn.
//
// THE SERVER NEVER MADE THIS IMAGE, and that is the whole reason the component carries a string. An
// image needs a URL, a URL is fetched, and a fetched URL puts a credential — which is what an
// activation code is — into a query string and into somebody's access log. Encoding it here keeps it
// inside the process that is allowed to see it, and the only thing that ever leaves is the screen.
//
// The library is an ENCODER rather than a QR widget: it answers a matrix of dark and light modules
// and draws nothing. So the code comes out in the design system's colours and the design system's
// corner radius, and a brand kit repaints it like everything else.
class EsimQrRenderer : KompotComponentRenderer<EsimQrComponent> {
    @Composable
    override fun Render(
        component: EsimQrComponent,
        actionHandler: KompotActionHandler,
        formController: FormController,
    ) {
        val designSystem = LocalKompotDesignSystem.current
        val surface = designSystem.resolveSurface(KompotSurfaceRoles.Container)

        // Encoded once per payload. The encoding is Reed–Solomon over a few hundred bytes — cheap,
        // and cheap per FRAME is a different question: a recomposition on every scroll would run it
        // again for a string that has not changed.
        val modules = remember(component.payload) { encode(component.payload) }

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Canvas(
                modifier =
                    Modifier
                        // THE CEILING GOES FIRST, and that is not a style choice.
                        //
                        // A fraction alone has no maximum: seven tenths of a phone is about 275
                        // points, seven tenths of a desktop window is however wide somebody dragged
                        // it, and at 900 the code became 630 and pushed the instruction, the typed
                        // code and both buttons off the screen — far enough that a Compose walk
                        // pressing the controls in order hit nothing and timed out (`B-74`).
                        //
                        // `.fillMaxWidth(f).widthIn(max = x)` does NOT cap it — measured, at 900 the
                        // code was still 630. `fillMaxWidth` resolves to an exact width and the later
                        // constraint does not shrink it. So the cap is on the width the FRACTION IS
                        // TAKEN OF, before it: below the cap a phone is unaffected, above it the
                        // code stops growing at seven tenths of the cap.
                        .widthIn(max = QR_LAYOUT_MAX_DP.dp)
                        .fillMaxWidth(QR_WIDTH_FRACTION)
                        // Square, because a QR that is not is a QR nothing reads.
                        .aspectRatio(1f)
                        .clip(
                            surface.shape ?: androidx.compose.foundation.shape
                                .RoundedCornerShape(20.dp),
                        )
                        // The QUIET ZONE is drawn rather than assumed: a scanner needs light around
                        // the code, and a card whose background happens to be light is not the same
                        // promise. Four modules is the specification's minimum.
                        .padding(QUIET_ZONE_DP.dp),
            ) {
                if (modules.isEmpty()) return@Canvas

                val moduleSize = size.minDimension / modules.size
                val dark = Color.Black

                modules.forEachIndexed { row, cells ->
                    cells.forEachIndexed { column, isDark ->
                        if (!isDark) return@forEachIndexed
                        drawRect(
                            color = dark,
                            topLeft = Offset(column * moduleSize, row * moduleSize),
                            // A hair over one module, so neighbouring cells meet instead of leaving
                            // a seam a scanner reads as a boundary.
                            size = Size(moduleSize + SEAM_BLEED, moduleSize + SEAM_BLEED),
                        )
                    }
                }
            }

            // BLACK ON WHITE AND NOT THE BRAND'S COLOURS, which is the one place this renderer
            // refuses the design system. A QR is read by a camera through a contrast threshold, and a
            // tasteful low-contrast pair is a code that scans on the designer's screen and not in a
            // hotel corridor. The card around it is themed; the code is not.

            component.captionText?.let { caption ->
                Text(
                    text = caption,
                    style = designSystem.resolveTypography(M3Typography.BodySmall),
                    color = designSystem.resolveColor(M3Colors.OnSurfaceVariant),
                    textAlign = TextAlign.Center,
                )
            }

            component.manualCodeText?.let { manual ->
                Text(
                    text = manual,
                    // The monospaced face the canvas asks for, so a code read aloud or typed by hand
                    // is read character by character. It is the design system's to choose, which is
                    // why this names a token rather than a family.
                    style = designSystem.resolveTypography(M3Typography.TitleMedium),
                    color = designSystem.resolveColor(M3Colors.OnSurface),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }

    internal companion object {
        const val QR_WIDTH_FRACTION = 0.7f

        // The widest this block is allowed to believe it has. Above a phone's own width, so the size
        // the product is for is untouched; the code itself therefore stops at 0.7 × 400 = 280 points,
        // which is a comfortable seven centimetres for a camera held up to a screen.
        const val QR_LAYOUT_MAX_DP = 400
        const val QUIET_ZONE_DP = 12
        const val SEAM_BLEED = 0.5f

        // MEDIUM error correction, which is the level a printed-on-a-screen code wants: it survives a
        // thumb over a corner and a camera at an angle, and costs about a fifth of the capacity. An
        // LPA activation code is short enough that the capacity is not the constraint.
        internal fun encode(payload: String): List<List<Boolean>> =
            try {
                QRCodeProcessor(payload, ErrorCorrectionLevel.MEDIUM)
                    .encode()
                    .map { row -> row.map { it.dark } }
            } catch (tooDense: InsufficientInformationDensityException) {
                // A payload too long to encode answers an empty matrix rather than throwing. The
                // screen then shows the caption and the typed code — which is the fallback the
                // component carries a `manualCodeText` for, and a blank square beats a crash on
                // somebody's install screen.
                emptyList()
            }
    }
}

// Named separately so a test can measure the matrix without a composition. The encoding is the half
// that can be wrong in a way a rendered picture hides: a grid of the right size that encodes the
// wrong thing looks exactly like one that does not.
internal fun encodeForTest(payload: String): List<List<Boolean>> = EsimQrRenderer.encode(payload)
