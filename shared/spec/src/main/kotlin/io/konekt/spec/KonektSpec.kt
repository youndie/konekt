package io.konekt.spec

import io.github.youndie.kompot.generated.generatedKonektSerializersModule
import io.github.youndie.kompot.spec.GeneratedSchema
import io.github.youndie.kompot.spec.KompotSpec
import io.github.youndie.kompot.spec.KompotSpecModule
import io.github.youndie.kompot.spec.KompotToolkitSpec
import io.konekt.feature.esim.shared.api.esimActionsSerializersModule
import io.konekt.feature.purchase.shared.api.purchaseActionsSerializersModule
import io.konekt.feature.shell.shared.api.shellActionsSerializersModule
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

// The eSIM feature's contribution: one action, which is konekt's whole application-level verb set.
//
// A module of its own rather than a line in the one above, because the toolkit's rule is one spec
// module per Gradle module and these are two — and because "konekt-components" describing an action
// would be a name that lies. It is also the module that would be easiest to forget: actions are
// registered by hand (research-architecture §1.13), so nothing generates a reminder.
fun konektEsimSpecModule(): KompotSpecModule =
    KompotSpecModule(
        name = "konekt-esim",
        description = "The eSIM install wizard's one action: which run to move, and which way.",
        serializersModule = esimActionsSerializersModule,
    )

// The purchase feature's contribution: `buy_plan`, konekt's second application-level verb.
//
// A module of its own for the same two reasons the eSIM one is: the toolkit's rule is one spec module
// per Gradle module, and it is the easiest thing in this build to forget. The conformance kit found
// exactly that — a `plan_card` carrying an action the profile did not declare, three times on one
// screen, the first time the walk saw the plans screen.
fun konektPurchaseSpecModule(): KompotSpecModule =
    KompotSpecModule(
        name = "konekt-purchase",
        description = "Buying a plan: the one verb whose destination is not knowable before it happens.",
        serializersModule = purchaseActionsSerializersModule,
    )

// The shell's contribution: `sign_out`, konekt's third verb.
//
// Its absence is what the conformance walk caught the first time it saw the profile screen — the
// action was registered on both sides, decoded perfectly, and was in no schema, so the kit reported
// it as a type outside the declared wire. That is the kit doing exactly its job: "both sides agree"
// and "the wire is described" are different claims, and only the second is what a second
// implementation can build against.
fun konektShellSpecModule(): KompotSpecModule =
    KompotSpecModule(
        name = "konekt-shell",
        description = "Leaving: the verb that ends a session on both sides at once.",
        serializersModule = shellActionsSerializersModule,
    )

// The spec of THIS build. The order matters — whoever comes first owns a shared definition — and
// konekt's modules go last because they define nothing the toolkit also defines and refer to plenty
// that it does.
object KonektSpec {
    val modules: List<KompotSpecModule> get() =
        KompotToolkitSpec.modules + konektSpecModule() + konektEsimSpecModule() +
            konektPurchaseSpecModule() + konektShellSpecModule()

    fun generateAll(): List<GeneratedSchema> = KompotSpec.generateAll(modules)

    // The closed list of what this build actually supports, as opposed to the open "any object with a
    // type" the module schemas describe. It is what a second implementation is held to.
    fun profile(): JsonObject = KompotSpec.profile(generateAll())
}
