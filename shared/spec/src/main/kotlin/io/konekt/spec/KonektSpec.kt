package io.konekt.spec

import io.github.youndie.kompot.generated.generatedKonektSerializersModule
import io.github.youndie.kompot.spec.GeneratedSchema
import io.github.youndie.kompot.spec.KompotSpec
import io.github.youndie.kompot.spec.KompotSpecModule
import io.github.youndie.kompot.spec.KompotToolkitSpec
import kotlinx.serialization.json.JsonObject

// konekt's own contribution to the wire specification: the nine component types of
// :shared:components, described the same way the toolkit describes its own modules — out of the very
// SerialDescriptors kotlinx.serialization encodes a response with, so the schema cannot fall quietly
// behind the types.
//
// One spec module per Gradle module, exactly as there is one schema file per module. That is the
// toolkit's rule and it is worth keeping rather than lumping everything into one file: a server
// implemented on another stack must be able to say which parts of this wire it speaks.
fun konektSpecModule(): KompotSpecModule =
    KompotSpecModule(
        name = "konekt-components",
        description =
            "The nine component types konekt adds to the kompot wire: " +
                "counters, plans, eSIM, orders and feedback.",
        serializersModule = generatedKonektSerializersModule,
    )

// The spec of THIS build. The order matters — whoever comes first owns a shared definition — and
// konekt's module goes last because it defines nothing the toolkit also defines and refers to plenty
// that it does.
object KonektSpec {
    val modules: List<KompotSpecModule> get() = KompotToolkitSpec.modules + konektSpecModule()

    fun generateAll(): List<GeneratedSchema> = KompotSpec.generateAll(modules)

    // The closed list of what this build actually supports, as opposed to the open "any object with a
    // type" the module schemas describe. It is what a second implementation is held to.
    fun profile(): JsonObject = KompotSpec.profile(generateAll())
}
