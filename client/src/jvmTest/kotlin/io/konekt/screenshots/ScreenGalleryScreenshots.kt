package io.konekt.screenshots

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import io.github.youndie.kompot.KompotActionHandler
import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.LocalKompotPageLoader
import io.github.youndie.kompot.LocalKompotRegistry
import io.github.youndie.kompot.decodeKompotComponent
import io.github.youndie.kompot.form.FormController
import io.github.youndie.kompot.form.FormSchema
import io.github.youndie.kompot.forms.KompotFormResponse
import io.github.youndie.kompot.standard.KompotPageLoader
import io.github.youndie.kompot.standard.KompotPageResponse
import io.konekt.client.net.konektClientJson
import ru.workinprogress.viddik.annotations.ViddikScreenshot

// EVERY SCREEN THIS BUILD SERVES, AT THE SIZE THE CANVAS DRAWS THEM, so the two can be held side by
// side. `RecordedHomeScreen` was one frame of this and carried the whole argument for the approach;
// this is the rest of the product on the same terms.
//
// ALL OF THEM ARE RECORDINGS. Not trees assembled here — a fixture that builds its own screen agrees
// with itself forever, and what these are for is noticing when the SERVER stops sending what the
// design asked for. Recorded off a running deployment: sign in, top up, buy the home plan, confirm,
// then read each address. What is committed is those answers verbatim.
//
// 393 WIDE, which is the canvas's frame. The HEIGHT is per screen, and that was not the first
// choice: one generous height for all of them makes two screens easy to hold side by side, and
// `GoldenContentTest` refused it — a frame that is 92% transparent is a photograph of an empty frame
// by the only measure a machine has, and the guard cannot tell my convenience from a capture that
// failed. Sizing each frame to its screen is the honest answer; comparing them is a person's job.
//
// Refreshing them is deliberate work rather than a build step. A recording that regenerated itself
// would agree with whatever the server does today, and agreeing with today is what a golden must not
// do.
private const val FRAME_WIDTH = 393

private class ScreenRecordings

private fun recording(name: String): String =
    ScreenRecordings::class.java
        .getResourceAsStream("/recorded/$name.json")
        ?.bufferedReader()
        ?.use { it.readText() }
        ?: error("no recording at /recorded/$name.json — a golden of nothing is not a golden")

private fun tree(name: String): KompotComponent = konektClientJson.decodeKompotComponent(recording(name))

private fun form(name: String): KompotFormResponse =
    konektClientJson.decodeFromString(KompotFormResponse.serializer(), recording(name))

// A screen that is a plain component tree: rendered as its own root, because wrapping it in a column
// of ours would photograph our frame around their screen and hide a root that arrived as something
// else entirely.
@Composable
private fun Screen(name: String) {
    BrandFrame(DEFAULT_BRAND) {
        // A LOADER THAT REFUSES, because a golden must photograph the page the server sent and not a
        // second one this fixture went and got. `PaginatedListRenderer` reads it out of a composition
        // local and throws when there is none — whether or not the list has a next page — which is how
        // the orders screen was found to crash the application the day a tab made it reachable.
        CompositionLocalProvider(LocalKompotPageLoader provides NoFurtherPages) {
            LocalKompotRegistry.current.RenderNode(
                component = tree(name),
                actionHandler = KompotActionHandler { },
                formController = FormController(FormSchema(formId = "gallery", fields = emptyList())),
            )
        }
    }
}

// Never called in a still frame — nothing scrolls — and it fails loudly rather than answering an
// empty page, so a golden that somehow paginated would say so instead of quietly showing less.
private val NoFurtherPages =
    object : KompotPageLoader {
        override suspend fun loadPage(
            url: String,
            params: Map<String, String>,
        ): KompotPageResponse = error("a screenshot asked for another page of $url, which a still frame cannot need")
    }

// A screen that is a `KompotFormResponse`: the tree plus the schema that binds it.
//
// THE CONTROLLER IS BUILT FROM THE RECORDED SCHEMA, not from an empty one, and that is the whole
// difference between this and the function above. The code screen's number is drawn by a BOUND
// `read_only_field` whose value lives in the schema's `initialValue` — hand it an empty controller
// and the field draws blank, which is a photograph of a bug this build does not have.
@Composable
private fun FormScreen(name: String) {
    BrandFrame(DEFAULT_BRAND) {
        val response = form(name)
        LocalKompotRegistry.current.RenderNode(
            component = response.screen,
            actionHandler = KompotActionHandler { },
            formController = FormController(response.schema),
        )
    }
}

@ViddikScreenshot(name = "Login", group = "Gallery", width = FRAME_WIDTH, height = 240, darkVariant = true)
@Composable
fun GalleryLogin() = FormScreen("login-screen")

@ViddikScreenshot(name = "Login code", group = "Gallery", width = FRAME_WIDTH, height = 300, darkVariant = true)
@Composable
fun GalleryLoginCode() = FormScreen("login-code-screen")

@ViddikScreenshot(name = "Home", group = "Gallery", width = FRAME_WIDTH, height = 300, darkVariant = true)
@Composable
fun GalleryHome() = Screen("home-screen")

@ViddikScreenshot(name = "Plans", group = "Gallery", width = FRAME_WIDTH, height = 730, darkVariant = true)
@Composable
fun GalleryPlans() = Screen("plans-screen")

// ORDERS IS NOT HERE, and the reason is the screen rather than the harness.
//
// It draws four lines of text and nothing else — no card, no surface, no rule — so a frame of it is
// 4% drawn and `GoldenContentTest` calls that a photograph of an empty frame. The guard is right to:
// at that coverage it cannot tell this screen from a capture that failed.
//
// Neither weakening the guard nor giving the screen a surface to satisfy it is a decision to make
// here. The canvas draws every order as a CARD (section 05), so the sparseness is a real delta and
// the missing golden is the cheapest possible record of it. The recording is committed beside the
// others and the frame comes back the day the row gets its surface.
@ViddikScreenshot(name = "Profile", group = "Gallery", width = FRAME_WIDTH, height = 370, darkVariant = true)
@Composable
fun GalleryProfile() = Screen("profile-screen")

@ViddikScreenshot(name = "Purchase result", group = "Gallery", width = FRAME_WIDTH, height = 230, darkVariant = true)
@Composable
fun GalleryPurchaseResult() = Screen("order-screen")
