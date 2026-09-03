package io.konekt.screenshots

import androidx.compose.runtime.CompositionLocalProvider
import io.github.youndie.kompot.spec.KompotProtocol
import io.github.youndie.kompot.standard.KompotPageLoader
import io.github.youndie.kompot.standard.KompotPageResponse
import io.github.youndie.kompot.spec.KompotSpec
import io.github.youndie.kompot.studio.KompotStudioConfig
import io.github.youndie.kompot.studio.source.ScreenSource
import io.konekt.client.net.konektClientJson
import io.konekt.client.render.konektRegistry
import io.konekt.client.theme.BrandKits
import io.konekt.spec.KonektSpec
import ru.workinprogress.viddik.LocalViddikDarkTheme
import kotlin.io.path.Path
import kotlin.io.path.absolute
import kotlin.io.path.exists

// THE STUDIO, ON THIS CLIENT (kompot B-14).
//
// Fifteen lines of configuration and not one of them new: the registry is the one the application
// composes with, the Json is the one the client decodes with, the frame is the fixture every golden
// in this package is already a photograph of, the vocabulary is this build's own spec, and the
// screens are the responses recorded off the running stand.
//
// That is the whole claim the pilot exists to test. A studio configured with anything assembled here
// would be a picture of a second client nobody ships — the same failure `BrandFrame`'s own comment
// warns about, met one level up.
fun studioConfig(): KompotStudioConfig {
    val schemas = KonektSpec.generateAll()

    return KompotStudioConfig(
        registry = konektRegistry(),
        json = konektClientJson,
        // BrandFrame reads `LocalViddikDarkTheme` because viddik cannot know which of konekt's
        // switches means dark. The studio asks with a parameter instead, so the adapter is one
        // provider — and the composition below the provider is byte for byte the one the goldens
        // photograph.
        frame = { brand, dark, content ->
            CompositionLocalProvider(LocalViddikDarkTheme provides dark) {
                BrandFrame(brand ?: DEFAULT_BRAND) { content() }
            }
        },
        brands = BrandKits.kits().keys.sorted(),
        schemas =
            schemas.associate { it.fileName to it.document } +
                (KompotProtocol.PROFILE_FILE_NAME to KompotSpec.profile(schemas)),
        sources = listOf(ScreenSource.Directory(recordingsDirectory(), name = "recorded")),
        // A STUB, and the toolkit's default is the opposite — deliberately, because a preview that
        // quietly supplies an empty page photographs a list ending where it does not. Here the
        // recorded orders screen is a `paginated_list`, and without a loader it cannot be opened at
        // all: the studio would refuse the one recording that has more than a screenful.
        //
        // The hazard the default guards against is real and now lives here instead: a frame CAPTURED
        // with this stub is not a golden. Filed against the toolkit as kompot B-24.
        pageLoader =
            object : KompotPageLoader {
                override suspend fun loadPage(
                    url: String,
                    params: Map<String, String>,
                ): KompotPageResponse = KompotPageResponse(items = emptyList())
            },
        snapshotsDirectory = repositoryRoot().resolve("client/src/jvmTest/snapshots"),
        goldenName = ::konektGoldenName,
    )
}

// `brand-a` becomes `Brand_A`, which is what viddik wrote when `@ViddikScreenshot(name = "A",
// group = "Brand")` produced these files. Nothing in the toolkit could have guessed that mapping,
// which is exactly why the studio asks for it.
fun konektGoldenName(
    brand: String?,
    dark: Boolean,
    screen: String,
): String {
    val name = brand?.substringAfterLast('-')?.uppercase() ?: screen
    return "Brand_$name${if (dark) "_Dark" else ""}.png"
}

// The same walk `BrandKits` does, and for the same reason: `:client` pins no working directory, so
// counting `..` from wherever Gradle started would turn a moved default into an empty directory that
// reads as "no recordings".
fun repositoryRoot(): java.nio.file.Path {
    var candidate = Path("").absolute()
    while (!candidate.resolve("settings.gradle.kts").exists()) {
        candidate = candidate.parent ?: error("no settings.gradle.kts above ${Path("").absolute()}")
    }
    return candidate
}

fun recordingsDirectory(): java.nio.file.Path =
    repositoryRoot().resolve("client/src/jvmTest/resources/recorded")
