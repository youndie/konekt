package io.konekt.client.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.youndie.kompot.KompotDesignSystem
import io.github.youndie.kompot.KompotSurface
import io.github.youndie.kompot.KompotSurfaceRoles
import io.github.youndie.kompot.material3.M3Colors
import io.github.youndie.kompot.theme.client.rememberKompotDesignSystem
import io.github.youndie.kompot.theme.kompotTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

// The guard for a bug that is already fixed, which is exactly when a guard is worth writing.
//
// `RemoteThemeDesignSystem` used to override `resolveColor` and `resolveTypography` and inherit the
// interface default for `resolveSurface` — so an application that customised its surfaces lost every
// one of them the moment the theme landed. The first frame was right and the second was Material's
// pill and an outlined field's border. Nothing threw and nothing logged, which is why it survived a
// release. kompot#80 closed it in 0.31.0.74.
//
// This test is deliberately written against THE TOOLKIT rather than against a wrapper of ours: what
// it asks is whether kompot still forwards the hook, not whether konekt works around it not doing so.
// Upstream has a reflective guard of its own (OverlayCoversEveryHookTest); this is the behavioural
// half, from the consumer's side, and it would fail on any version that regressed.
@OptIn(ExperimentalTestApi::class)
class SurfaceSurvivesTheThemeTest {
    // Colours only. That is the shape of a real brand kit — a server theme says nothing about
    // surfaces and cannot, because the wire has no vocabulary for shape (research §1.2) — and it is
    // the shape that used to erase them.
    private val coloursOnly =
        kompotTheme("brand-b") {
            light {
                color(M3Colors.Primary, "#FF00695C")
                color(M3Colors.OnPrimary, "#FFFFFFFF")
                color(M3Colors.Surface, "#FFF3F6F5")
                color(M3Colors.SurfaceVariant, "#FFDCE5E3")
                color(M3Colors.OnSurfaceVariant, "#FF12211E")
            }
        }

    private fun surfacesUnderTheTheme(): Pair<Map<String, KompotSurface>, Map<String, KompotSurface>> {
        var own: Map<String, KompotSurface>? = null
        var throughTheme: Map<String, KompotSurface>? = null
        var wrapped: KompotDesignSystem? = null
        var konekt: KompotDesignSystem? = null

        runComposeUiTest {
            setContent {
                MaterialTheme {
                    val konektDesignSystem = KonektDesignSystem()
                    val themed = rememberKompotDesignSystem(coloursOnly, konektDesignSystem)

                    konekt = konektDesignSystem
                    wrapped = themed
                    own = ROLES.associateWith { konektDesignSystem.resolveSurface(it.asRole()) }
                    throughTheme = ROLES.associateWith { themed.resolveSurface(it.asRole()) }
                }
            }
        }

        // THE POSITIVE CONTROL, and without it this file proves nothing. `rememberKompotDesignSystem`
        // returns the fallback UNCHANGED when the theme is null, so every assertion below would pass
        // on a run where no theme ever arrived — which is the one state the test exists to rule out.
        assertNotSame(konekt, wrapped, "no theme was applied, so nothing here was actually tested")

        return assertNotNull(own) to assertNotNull(throughTheme)
    }

    @Test
    fun `a theme that describes colours answers for no surface at all`() {
        val (own, throughTheme) = surfacesUnderTheTheme()

        // Per role rather than in aggregate: a single spot check passes on a forwarding that covers
        // the role it was written for and drops the next one, which is the shape the original defect
        // had.
        ROLES.forEach { role ->
            assertEquals(own[role], throughTheme[role], "the theme replaced the surface for '$role'")
        }
    }

    @Test
    fun `the field is still borderless once the theme has landed`() {
        val (_, throughTheme) = surfacesUnderTheTheme()
        val field = assertNotNull(throughTheme[KompotSurfaceRoles.Field.key])

        // Transparent and not Unspecified, and the distinction is the whole assertion: Unspecified
        // means "whatever the toolkit draws for this role", which for a field is a border. This is
        // what the canvas asks for and what the defect used to take away.
        assertEquals(Color.Transparent, field.outline)
        assertNotNull(field.shape, "the field lost its corner radius")
    }

    @Test
    fun `the button is still a pill once the theme has landed`() {
        val (own, throughTheme) = surfacesUnderTheTheme()

        val button = assertNotNull(throughTheme[KompotSurfaceRoles.Button.key])
        assertNotNull(button.shape, "the button fell back to Material's own shape")
        assertEquals(own[KompotSurfaceRoles.Button.key]?.shape, button.shape)

        // The quiet variant too, because a role composed from a variant is a different key and a
        // forwarding that covers one need not cover the other.
        assertTrue(throughTheme.containsKey(KompotSurfaceRoles.button("quiet").key))
        assertEquals(
            own[KompotSurfaceRoles.button("quiet").key],
            throughTheme[KompotSurfaceRoles.button("quiet").key],
        )
    }

    private companion object {
        // Keyed by the string rather than by SurfaceRole so a failure names the role in words.
        val ROLES =
            listOf(
                KompotSurfaceRoles.Button.key,
                KompotSurfaceRoles.button("primary").key,
                KompotSurfaceRoles.button("quiet").key,
                KompotSurfaceRoles.Field.key,
                KompotSurfaceRoles.ReadOnlyField.key,
                KompotSurfaceRoles.Container.key,
            )
    }
}

private fun String.asRole() =
    io.github.youndie.kompot
        .SurfaceRole(this)
