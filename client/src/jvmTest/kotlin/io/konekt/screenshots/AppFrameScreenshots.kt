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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import ru.workinprogress.viddik.LocalViddikDarkTheme
import ru.workinprogress.viddik.annotations.ViddikScreenshot

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
private const val APP_WIDTH = 393
private const val APP_HEIGHT = 852

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
private class Recorded(
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
        )
    }
}

@ViddikScreenshot(name = "App home", group = "AppFrame", width = APP_WIDTH, height = APP_HEIGHT, darkVariant = true)
@Composable
fun AppHome() = App("home-screen")

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

@ViddikScreenshot(name = "App login", group = "AppFrame", width = APP_WIDTH, height = APP_HEIGHT, darkVariant = true)
@Composable
fun AppLogin() = App("login-screen")
