package io.konekt.client.render

import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.KompotComponentRenderer
import io.github.youndie.kompot.KompotRegistry
import io.github.youndie.kompot.UnknownComponent
import io.github.youndie.kompot.kompotCoreRenderers
import io.github.youndie.kompot.kompotStandardRenderers
import io.konekt.components.EsimQrComponent
import io.konekt.components.UsageCounterCardComponent
import kotlin.reflect.KClass

// konekt's own renderers, and the list is deliberately short of the dictionary.
//
// The nine wire types are fixed (`konektWireNames`); the renderers arrive one screen at a time, and
// a type with no renderer is not a defect — it draws the unknown-component block, which is exactly
// the degradation the whole additive-dictionary argument rests on. What WOULD be a defect is nobody
// knowing which is which, so `KonektRendererCoverageTest` names both sets and fails when they move
// apart without a line saying so.
val konektRenderers: Map<KClass<out KompotComponent>, KompotComponentRenderer<out KompotComponent>> =
    mapOf(
        UsageCounterCardComponent::class to UsageCounterCardRenderer(),
        EsimQrComponent::class to EsimQrRenderer(),
        // REPLACES the toolkit's entry, which is why order matters below: `kompotCoreRenderers +
        // konektRenderers` puts ours last and last wins. The toolkit's default draws nothing when the
        // server named no fallback, and a hole is indistinguishable from a screen that failed to load.
        UnknownComponent::class to UnknownBlockRenderer(),
    )

// The registry an application hands to `LocalKompotRegistry`: the toolkit's own renderers plus ours.
//
// Assembled here rather than at each call site, because two registries that differ by one entry are
// two clients, and the second one is discovered by a screen that draws a blank where the first drew
// a card.
fun konektRegistry(): KompotRegistry = KompotRegistry(kompotCoreRenderers + kompotStandardRenderers + konektRenderers)
