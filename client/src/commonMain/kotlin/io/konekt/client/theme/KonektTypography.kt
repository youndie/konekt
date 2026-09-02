package io.konekt.client.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.konekt.client.generated.resources.Res
import io.konekt.client.generated.resources.manrope_400
import io.konekt.client.generated.resources.manrope_500
import io.konekt.client.generated.resources.manrope_600
import io.konekt.client.generated.resources.manrope_700
import io.konekt.client.generated.resources.manrope_800
import io.konekt.client.generated.resources.space_grotesk_400
import io.konekt.client.generated.resources.space_grotesk_500
import io.konekt.client.generated.resources.space_grotesk_600
import io.konekt.client.generated.resources.space_grotesk_700
import org.jetbrains.compose.resources.Font

// THE CANVAS'S TYPE SCALE, in one place (`B-114` G1 and G5).
//
// kompot's material design system answers every `TypographyToken` from `MaterialTheme.typography`,
// so this is the one object that decides what a title, a body and a label look like on every screen.
// Before it existed the client drew Material's defaults in the platform's sans — SF on a Mac, Roboto
// on a phone — and the canvas is set in two faces that are neither: Manrope for everything a person
// reads and Space Grotesk for everything they count. Nothing on any screen reads right without them.
//
// TWO FAMILIES, NOT ONE. The figures — a balance, a price, a code, an ICCID — are the grotesk, and
// they are what a screen is about; the sentences around them are Manrope. Material's scale has no
// notion of "a number", so the split is made here by role: `display*` and `headline*` are figures,
// everything else is text. A renderer that draws a price with `TitleLarge` gets the wrong face and
// should ask for a display style instead.
//
// THE SIZES ARE THE CANVAS'S, read off its token block: title 24/700, body 14/500, label 12/600,
// display 44 for the balance. Weights lean heavier than Material's defaults on purpose — the canvas
// sets its titles at 700 and its labels at 600, and at 400 the same words look unfinished.
object KonektTypography {
    // STATIC INSTANCES, ONE FILE PER WEIGHT, cut from google/fonts' variable files with fonttools
    // — and static rather than variable is a measured decision, not a preference. With the variable
    // file, the same screen recorded on a Mac and verified on the Linux CI runner differed by
    // 0.07–0.26% of its pixels: a variable font is instanced by the platform's engine (CoreText on
    // one, FreeType on the other) before Skia ever sees an outline, and the two do not agree to the
    // pixel. A static instance is one outline everywhere. Vertical metrics are equalised in the same
    // cut (hhea = win = typo, `USE_TYPO_METRICS` set), for the same reason and the other axis.
    // Licences beside the design in `docs/design/fonts/`.
    @Composable
    private fun manrope(): FontFamily =
        FontFamily(
            Font(Res.font.manrope_400, FontWeight.Normal),
            Font(Res.font.manrope_500, FontWeight.Medium),
            Font(Res.font.manrope_600, FontWeight.SemiBold),
            Font(Res.font.manrope_700, FontWeight.Bold),
            Font(Res.font.manrope_800, FontWeight.ExtraBold),
        )

    @Composable
    private fun spaceGrotesk(): FontFamily =
        FontFamily(
            Font(Res.font.space_grotesk_400, FontWeight.Normal),
            Font(Res.font.space_grotesk_500, FontWeight.Medium),
            Font(Res.font.space_grotesk_600, FontWeight.SemiBold),
            Font(Res.font.space_grotesk_700, FontWeight.Bold),
        )

    // A COMPOSABLE, because a resource font is resolved in composition. Read once per theme by
    // `KonektTheme`; nothing else should ask for it.
    val material: Typography
        @Composable
        get() =
            typography(text = manrope(), figures = spaceGrotesk())

    private fun typography(
        text: FontFamily,
        figures: FontFamily,
    ): Typography {
        fun text(
            size: androidx.compose.ui.unit.TextUnit,
            weight: FontWeight,
            lineHeight: androidx.compose.ui.unit.TextUnit,
            letterSpacing: androidx.compose.ui.unit.TextUnit = 0.sp,
        ) = TextStyle(
            fontFamily = text,
            fontSize = size,
            fontWeight = weight,
            lineHeight = lineHeight,
            letterSpacing = letterSpacing,
        )

        fun figures(
            size: androidx.compose.ui.unit.TextUnit,
            weight: FontWeight,
            lineHeight: androidx.compose.ui.unit.TextUnit,
        ) = TextStyle(
            fontFamily = figures,
            fontSize = size,
            fontWeight = weight,
            lineHeight = lineHeight,
            letterSpacing = (-0.5).sp,
        )
        return Typography(
            // WHAT THE SCREENS ACTUALLY ASK FOR, mapped to what the canvas draws there — the
            // tokens were chosen by the server long before this scale existed, so the scale
            // meets them rather than the other way round. `display_small` is the balance (44 in
            // the canvas), `headline_medium` a package's figure (28), `headline_small` every
            // screen's title (26, and Manrope: a title is read, not counted).
            displayLarge = figures(56.sp, FontWeight.Bold, lineHeight = 60.sp),
            displayMedium = figures(48.sp, FontWeight.Bold, lineHeight = 52.sp),
            displaySmall = figures(44.sp, FontWeight.Bold, lineHeight = 48.sp),
            headlineLarge = figures(32.sp, FontWeight.Bold, lineHeight = 36.sp),
            headlineMedium = figures(28.sp, FontWeight.Bold, lineHeight = 32.sp),
            headlineSmall = text(26.sp, FontWeight.Bold, lineHeight = 30.sp, letterSpacing = (-0.3).sp),
            titleLarge = text(24.sp, FontWeight.Bold, lineHeight = 28.sp, letterSpacing = (-0.2).sp),
            titleMedium = text(18.sp, FontWeight.Bold, lineHeight = 22.sp),
            titleSmall = text(15.sp, FontWeight.SemiBold, lineHeight = 20.sp),
            bodyLarge = text(16.sp, FontWeight.Medium, lineHeight = 22.sp),
            bodyMedium = text(14.sp, FontWeight.Medium, lineHeight = 20.sp),
            bodySmall = text(12.sp, FontWeight.Medium, lineHeight = 16.sp),
            labelLarge = text(14.sp, FontWeight.Bold, lineHeight = 20.sp),
            labelMedium = text(12.sp, FontWeight.SemiBold, lineHeight = 16.sp),
            labelSmall = text(11.5.sp, FontWeight.Medium, lineHeight = 16.sp, letterSpacing = 0.2.sp),
        )
    }
}
