package io.konekt.screenshots

import androidx.compose.runtime.Composable
import io.github.youndie.kompot.KompotAction
import io.github.youndie.kompot.KompotActionHandler
import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.KompotScreen
import io.github.youndie.kompot.decodeKompotComponent
import io.github.youndie.kompot.form.FormController
import io.github.youndie.kompot.form.FormSchema
import io.github.youndie.kompot.form.PatchFetcher
import io.github.youndie.kompot.forms.KompotFormResponse
import io.github.youndie.kompot.standard.KompotPageLoader
import io.github.youndie.kompot.standard.KompotPageResponse
import io.github.youndie.kompot.theme.KompotTheme
import io.konekt.client.app.ComponentUpdate
import io.konekt.client.app.KonektApp
import io.konekt.client.app.KonektFormScreen
import io.konekt.client.app.Screen
import io.konekt.client.app.ScreenSource
import io.konekt.client.net.konektClientJson
import io.konekt.client.render.konektRegistry
import io.konekt.client.theme.BrandKits
import io.konekt.client.theme.KonektTypography
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import ru.workinprogress.viddik.LocalViddikDarkTheme
import ru.workinprogress.viddik.annotations.ViddikScreenshot
import ru.workinprogress.viddik.core.viddikTypography

// THE APPLICATION, PHOTOGRAPHED — which the gallery beside this file does not do, and that omission
// is why every frame-level defect in this product was found by a person and none by the harness.
//
// `ScreenGalleryScreenshots` hands a recorded tree straight to `RenderNode`. What it photographs is
// therefore the SCREEN and never the application: the margin, the ground behind it, the scroll
// container, the bar lifted to the bottom of the window and the back control are all drawn by
// `KonektApp`, and none of them are in the frame. A missing margin, a bar landing a third of the way
// down the home screen, an unpainted ground, a back control on a tab — six defects, all of them
// invisible to a green suite, all of them obvious in the first second of looking at the running app.
//
// So this file draws the same recordings through the real composition root. The only thing faked is
// the socket.
//
// 852 TALL, which is the canvas's frame height rather than the screen's content height. That is the
// point: the gallery sizes each frame to its content, so a bar that lands in the middle of the window
// has no window to land in the middle OF. A defect about where something sits inside a phone needs a
// phone-shaped frame.
internal const val APP_WIDTH = 393
internal const val APP_HEIGHT = 852

private class AppRecordings

private fun body(name: String): String =
    AppRecordings::class.java
        .getResourceAsStream("/recorded/$name.json")
        ?.bufferedReader()
        ?.use { it.readText() }
        ?: error("no recording at /recorded/$name.json — a golden of nothing is not a golden")

// ONE ADDRESS, ONE ANSWER, and the shape is decided by the body exactly as `KonektScreenSource`
// decides it: a form response carries `schema`, a screen does not. Reproducing that choice rather
// than hard-coding it per frame keeps this fixture honest about which of the two a recording is.
internal class Recorded(
    private val name: String,
) : ScreenSource {
    override suspend fun fetch(address: String): Screen {
        val text = body(name)
        return if ("\"schema\"" in text.substringBefore("\"screen\"")) {
            Screen.Form(konektClientJson.decodeFromString(KompotFormResponse.serializer(), text))
        } else {
            Screen.Tree(konektClientJson.decodeKompotComponent(text))
        }
    }

    // NO GRAPH. A still frame navigates nowhere, and a fixture that answered one would let a golden
    // depend on which destinations a deployment happened to serve.
    override suspend fun navigation(): Map<String, String>? = null

    // The kit comes from the fixture's own frame, not from here: these cases are recorded per brand
    // and a second source of palette would be a second answer to the same question.
    override suspend fun brandTheme(): KompotTheme? = null

    override suspend fun fetchForm(address: String): KompotFormResponse =
        konektClientJson.decodeFromString(KompotFormResponse.serializer(), body(name))

    override fun patchFetcher(
        address: String,
        formId: String,
    ): PatchFetcher = throw UnsupportedOperationException("a still frame patches nothing")

    // Fails rather than answering an empty page, so a frame that somehow paginated says so instead
    // of quietly showing a shorter list.
    override fun pages(): KompotPageLoader =
        object : KompotPageLoader {
            override suspend fun loadPage(
                url: String,
                params: Map<String, String>,
            ): KompotPageResponse = error("a screenshot asked for another page of $url")
        }

    override fun updates(topic: String) = MutableSharedFlow<ComponentUpdate>().asSharedFlow()

    override val streamRestarted = MutableSharedFlow<Unit>().asSharedFlow()

    @Composable
    override fun renderNode(
        component: KompotComponent,
        onAction: (KompotAction) -> Unit,
    ) {
        konektRegistry().RenderNode(
            component = component,
            actionHandler = KompotActionHandler { onAction(it) },
            formController = FormController(FormSchema(formId = "app-frame", fields = emptyList())),
        )
    }

    @Composable
    override fun render(
        screen: Screen,
        onAction: (KompotAction) -> Unit,
    ) {
        when (screen) {
            is Screen.Form -> {
                KonektFormScreen(
                    response = screen.response,
                    registry = konektRegistry(),
                    patchFetcher = null,
                    onAction = onAction,
                    submit = { _, _ -> },
                )
            }

            is Screen.Tree -> {
                KompotScreen(
                    rootComponent = screen.component,
                    registry = konektRegistry(),
                    formController = FormController(FormSchema(formId = "app-frame", fields = emptyList())),
                    actionHandler = KompotActionHandler { onAction(it) },
                )
            }
        }
    }
}

@Composable
private fun App(
    name: String,
    brand: String = DEFAULT_BRAND,
) {
    // `BrandFrame` supplies the kit and the dark switch viddik asks for; `KonektApp` is handed the
    // same kit so it paints its ground from the brand rather than from Material's default.
    BrandFrame(brand) {
        KonektApp(
            screens = Recorded(name),
            address = "/recorded/$name",
            topic = "screenshots",
            darkMode = LocalViddikDarkTheme.current,
            theme = BrandKits.kits().getValue(brand),
            // The product's scale on viddik's family — `BrandFrame` says why, and this frame has to
            // say it again because `KonektApp` builds its own theme inside it.
            typography = viddikTypography(KonektTypography.material),
        )
    }
}

@ViddikScreenshot(name = "App home", group = "AppFrame", width = APP_WIDTH, height = APP_HEIGHT, darkVariant = true)
@Composable
fun AppHome() = App("home-screen")

// THE HOME SCREEN IN A DESKTOP WINDOW, and it is here because `B-109` was invisible without a frame
// that puts two DIFFERENT kinds of card next to each other and shows their left edges.
//
// The allowance block is a `surface` and the travel package under it is a `usage_counter_card`; they
// were inset by 20 and 16, because each renderer spelled its own number, and side by side that is a
// step in a line that should be straight. At a phone's width it is four pixels and easy to argue
// with; at a window's width the same four pixels sit under a much longer line and are not.
@ViddikScreenshot(name = "App home wide", group = "AppFrame", width = 1100, height = 1000)
@Composable
fun AppHomeWide() = App("home-screen")

@ViddikScreenshot(name = "App plans", group = "AppFrame", width = APP_WIDTH, height = APP_HEIGHT, darkVariant = true)
@Composable
fun AppPlans() = App("plans-screen")

@ViddikScreenshot(name = "App orders", group = "AppFrame", width = APP_WIDTH, height = APP_HEIGHT, darkVariant = true)
@Composable
fun AppOrders() = App("orders-screen")

@ViddikScreenshot(name = "App profile", group = "AppFrame", width = APP_WIDTH, height = APP_HEIGHT, darkVariant = true)
@Composable
fun AppProfile() = App("profile-screen")

@ViddikScreenshot(
    name = "App plan detail",
    group = "AppFrame",
    width = APP_WIDTH,
    height = APP_HEIGHT,
    darkVariant = true,
)
@Composable
fun AppPlanDetail() = App("plan-detail-screen")

// THE STATE SECTION 03 DRAWS AND NOTHING PHOTOGRAPHED. The confirmation is where a subscriber agrees
// to spend, and it had no frame at all — which is the same gap that let its whole copy be rewritten
// with a green suite. It goes here rather than in the gallery because it is text, a banner and two
// buttons on no card: `GoldenContentTest` reads a frame of that as too thin to tell from a capture
// that failed, and it is right to.
@ViddikScreenshot(
    name = "App confirm",
    group = "AppFrame",
    width = APP_WIDTH,
    height = APP_HEIGHT,
    darkVariant = true,
)
@Composable
fun AppConfirm() = App("confirm-screen")

// THE REFUSAL, WHICH NOTHING PHOTOGRAPHED while it was one sentence for five different reasons.
//
// It is the frame a first-time subscriber gets by pressing the first thing they see — no money yet,
// so the saga is refused before anything is held — and the gallery had only the COMPLETED order. A
// state nobody looks at is a state whose copy can be rewritten by a green suite, which is exactly
// what `B-68` was: the sentence named none of its five causes and the only control was `Back`.
//
// In the app frame rather than the gallery for the same reason the confirmation is: a banner and two
// buttons on no card is a frame `GoldenContentTest` reads as too thin to tell from a failed capture.
@ViddikScreenshot(
    name = "App purchase refused",
    group = "AppFrame",
    width = APP_WIDTH,
    height = APP_HEIGHT,
    darkVariant = true,
)
@Composable
fun AppPurchaseRefused() = App("order-refused-screen")

// THE TWO STEPS OF THE INSTALL FLOW THAT NOTHING PHOTOGRAPHED, and the flow's only screens with a
// size question on them.
//
// `App esim install` is step ONE — a paragraph and a button. The step that hands over the activation
// code, and the one that confirms it, are where a 361-point square meets an 852-point phone, and
// neither had a frame. `B-74` was reported off a DESKTOP window, where the code stretches to the
// window's width and buries everything; these two exist so the question is asked at the size the
// product is for, rather than at whatever size somebody's window happened to be.
@ViddikScreenshot(
    name = "App esim activate",
    group = "AppFrame",
    width = APP_WIDTH,
    height = APP_HEIGHT,
    darkVariant = true,
)
@Composable
fun AppEsimActivate() = App("esim-activate-screen")

@ViddikScreenshot(
    name = "App esim done",
    group = "AppFrame",
    width = APP_WIDTH,
    height = APP_HEIGHT,
    darkVariant = true,
)
@Composable
fun AppEsimDone() = App("esim-done-screen")

// THE SAME STEP IN A DESKTOP WINDOW, which is the only frame in this file that is not phone-shaped
// and the only one where the code's ceiling is visible at all.
//
// `fillMaxWidth(0.7f)` has no maximum, so at 900 points the code was 630 and everything under it left
// the screen. On a phone it was always fine, which is why the frames above show nothing wrong and why
// this one has to exist: a size that is right at one width and absurd at another cannot be
// photographed at one width.
@ViddikScreenshot(
    name = "Esim activate wide",
    group = "AppFrame",
    width = 900,
    height = 700,
    darkVariant = false,
)
@Composable
fun EsimActivateWide() = App("esim-activate-screen")

@ViddikScreenshot(
    name = "App esim install",
    group = "AppFrame",
    width = APP_WIDTH,
    height = APP_HEIGHT,
    darkVariant = true,
)
@Composable
fun AppEsimInstall() = App("esim-install-screen")

// BOUGHT AND NOT YET INSTALLED — the state a subscriber is in between paying and scanning, and the
// one no frame in this repository showed.
//
// Both screens got it wrong in the same direction and for the same reason: one number meant profiles
// HELD, the profile screen printed it under the word "installed", and the home screen used it to
// decide whether to offer the install flow at all. So the person who most needed the door was told
// they had already walked through it (`B-69`). Two frames rather than one, because the defect was two
// screens disagreeing about one question.
@ViddikScreenshot(
    name = "App home uninstalled",
    group = "AppFrame",
    width = APP_WIDTH,
    height = APP_HEIGHT,
    darkVariant = true,
)
@Composable
fun AppHomeUninstalled() = App("home-uninstalled-screen")

@ViddikScreenshot(
    name = "App profile uninstalled",
    group = "AppFrame",
    width = APP_WIDTH,
    height = APP_HEIGHT,
    darkVariant = true,
)
@Composable
fun AppProfileUninstalled() = App("profile-uninstalled-screen")

@ViddikScreenshot(name = "App login", group = "AppFrame", width = APP_WIDTH, height = APP_HEIGHT, darkVariant = true)
@Composable
fun AppLogin() = App("login-screen")
