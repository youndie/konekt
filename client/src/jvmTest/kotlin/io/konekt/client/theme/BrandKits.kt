package io.konekt.client.theme

import io.github.youndie.kompot.theme.KompotTheme
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.absolute
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText
import kotlin.test.fail

// THE KITS THE SERVER ACTUALLY SHIPS, read off disk rather than retyped into a fixture.
//
// A client test reaching into `server/src/main/resources` looks like the wrong direction until you
// notice what is being tested. A brand kit is not server code — it is a WIRE DOCUMENT, produced by
// the server and consumed by the client, and the properties worth guarding are all statements about
// what the client can do with it: does it cover every token this build resolves, does every value
// parse, is there a shape scale compiled in for its brand. None of that is answerable on the server,
// which does not know what a `ColorToken` vocabulary is.
//
// The alternative is a copy of the palette in a Kotlin fixture, and a copy is a test that agrees with
// itself while the served file drifts.
object BrandKits {
    private val json = Json { ignoreUnknownKeys = true }

    // Resolved by walking up to the repository root rather than by counting `..` from a working
    // directory nobody has pinned. `:client` sets no `workingDir`, so the Gradle default is what
    // decides, and a default that moved would otherwise turn every assertion below into a pass over
    // an empty list.
    val directory: Path by lazy {
        var candidate = Path("").absolute()
        while (!candidate.resolve("settings.gradle.kts").exists()) {
            candidate = candidate.parent ?: fail("no settings.gradle.kts above ${Path("").absolute()}")
        }

        candidate.resolve("server/src/main/resources/themes").also {
            if (!it.isDirectory()) fail("the brand kits are not at $it")
        }
    }

    val files: List<Path> by lazy {
        directory.listDirectoryEntries("*.json").sortedBy { it.name }
    }

    // Keyed by FILE NAME and not by the `id` inside, so that a kit whose two names disagree is a
    // visible disagreement rather than a map with one entry silently overwritten.
    fun kits(): Map<String, KompotTheme> =
        files.associate {
            it.nameWithoutExtension to
                json.decodeFromString(it.readText())
        }
}
