package io.konekt.theme

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

// The operator's brand kit, chosen by configuration and read once at startup.
//
// WHAT AN OPERATOR CAN CHANGE HERE IS COLOUR, AND ONLY COLOUR. `kompot-core` has exactly two token
// kinds — `ColorToken` and `TypographyToken` — and nothing that names a shape, so a brand's radii
// cannot travel and are a constant in the client build (research-architecture §1.2, D2). The half
// that ships from here is `server/src/main/resources/themes/<brand>.json`; the half that ships with
// the application is `KonektShapeScale`. docs/design/design-brand-kit.md is the operator-facing
// statement of that split, and it exists because the alternative is an operator discovering it.
//
// THE DOCUMENT IS SERVED AS BYTES AND NOT DECODED AND RE-ENCODED, which is a decision and not a
// shortcut. A `KompotTheme` this server parsed would be a `KompotTheme` at the version this server
// was built against: a field the toolkit adds next release would be silently dropped on the way
// through, and the client — which is the thing that actually understands the schema — would never see
// it. Passing the bytes through means the server does not have to know what a theme is, which is what
// makes a brand a configuration rather than a release.
//
// What it DOES check is what a pass-through can check without knowing the schema, and all three
// failures are ones that would otherwise be found by a subscriber:
//
//   * the named kit exists at all;
//   * it is a JSON object rather than, say, a half-written file;
//   * its `id` is the brand it was asked for. A kit copied to start the next brand and never renamed
//     inside is served under the new name with the old identity — and because the CLIENT resolves its
//     shape scale from that `id`, the result is the new brand's colours drawn with the old brand's
//     radii, which reads as a rendering bug.
//
// Read at startup, so all three are a process that will not start rather than a screen that fails
// under a user — the same bargain `KonektConfig` makes with a missing environment variable.
class BrandThemeCatalogue(
    val brand: String,
) {
    val document: String = load(brand)

    private companion object {
        const val DIRECTORY = "/themes"

        // ignoreUnknownKeys is irrelevant here — nothing is decoded into a class — but the parse has
        // to be lenient about what a theme carries, because this server deliberately does not know.
        private val parser = Json

        fun load(brand: String): String {
            val path = "$DIRECTORY/$brand.json"
            val text =
                BrandThemeCatalogue::class.java.getResourceAsStream(path)?.use { it.readBytes().decodeToString() }
                    ?: error("no brand kit at $path — the brand is chosen by configuration and this one does not exist")

            val root =
                parser.parseToJsonElement(text) as? JsonObject
                    ?: error("$path is not a JSON object, so it cannot be a brand kit")

            val id = root["id"]?.jsonPrimitive?.content
            check(id == brand) {
                "$path carries the brand id '$id'. The client resolves its shape scale from that id, " +
                    "so serving it under the name '$brand' would draw one brand's colours with another's radii"
            }

            return text
        }
    }
}
