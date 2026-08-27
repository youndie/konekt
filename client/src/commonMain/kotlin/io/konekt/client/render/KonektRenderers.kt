package io.konekt.client.render

import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.KompotComponentRenderer
import io.github.youndie.kompot.KompotRegistry
import io.github.youndie.kompot.UnknownComponent
import io.github.youndie.kompot.generated.generatedFormsClientRenderers
import io.github.youndie.kompot.kompotCoreRenderers
import io.github.youndie.kompot.kompotStandardRenderers
import io.github.youndie.kompot.standard.ColumnComponent
import io.github.youndie.kompot.standard.RowComponent
import io.konekt.components.BannerComponent
import io.konekt.components.BottomNavComponent
import io.konekt.components.EsimCardComponent
import io.konekt.components.EsimQrComponent
import io.konekt.components.OrderRowComponent
import io.konekt.components.PlanCardComponent
import io.konekt.components.SkeletonComponent
import io.konekt.components.SnackbarComponent
import io.konekt.components.StepMeterComponent
import io.konekt.components.SurfaceComponent
import io.konekt.components.UsageCounterCardComponent
import kotlin.reflect.KClass

// EVERY DICTIONARY TYPE HAS A RENDERER NOW, and the six below draw the degradation block on purpose.
//
// They used to have none, which is a different thing from drawing a block: `KompotRegistry.RenderNode`
// found nothing and the toolkit's own fallback drew red text, reaching no sink and no record. A screen
// made of them was silent from an operator's side — and `banner` was in this list while the home
// screen sent one to every subscriber who had bought nothing.
//
// Registering them is what makes the gap VISIBLE rather than what fixes it. The fix is a renderer per
// type, one screen at a time (`B-45`); until then a served type nobody wired up looks to a subscriber
// exactly like a type from a newer server, and to an operator like neither.
private val undrawn: Map<KClass<out KompotComponent>, KompotComponentRenderer<out KompotComponent>> =
    mapOf(
        // EMPTY, AND THE MAP IS KEPT. Every one of the nine dictionary types has a renderer of its
        // own now (`B-45`), so nothing draws the block on purpose any more — but the mechanism is
        // what makes the NEXT type added to the dictionary visible rather than silent, and deleting
        // it would take the guard with it.
    )

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
        // The shell's bar, which arrives inside every tab screen's tree rather than being
        // fetched once — the server is the side that knows which tab it just built.
        BottomNavComponent::class to BottomNavRenderer(),
        EsimQrComponent::class to EsimQrRenderer(),
        // The banner the home screen sends to a subscriber who has bought nothing — which is every
        // subscriber's FIRST screen, and which drew a red "Unknown component" until an application
        // on a phone showed it. See `BannerRenderer`: a type that decodes and has no renderer is not
        // an `UnknownComponent`, so the degradation block never covered it.
        BannerComponent::class to BannerRenderer(),
        // The six that drew the degradation block deliberately until B-45. Each is the screen it was
        // waiting for arriving, and every string on every one of them is composed by the server.
        PlanCardComponent::class to PlanCardRenderer(),
        EsimCardComponent::class to EsimCardRenderer(),
        OrderRowComponent::class to OrderRowRenderer(),
        SnackbarComponent::class to SnackbarRenderer(),
        StepMeterComponent::class to StepMeterRenderer(),
        SkeletonComponent::class to SkeletonRenderer(),
        // The one container, and the one meant to be deleted when U14 lands upstream.
        SurfaceComponent::class to SurfaceRenderer(),
        // REPLACES the toolkit's entry, which is why order matters below: `kompotCoreRenderers +
        // konektRenderers` puts ours last and last wins. The toolkit's default draws nothing when the
        // server named no fallback, and a hole is indistinguishable from a screen that failed to load.
        UnknownComponent::class to UnknownBlockRenderer(),
        // REPLACING the toolkit's containers too, and for one line each: they provide the density an
        // unknown block is drawn at. The CARD branch was unreachable from any screen before this —
        // declared, tested by a fixture that supplied its own condition, and provided by nothing.
        ColumnComponent::class to ColumnDensityRenderer(),
        RowComponent::class to RowDensityRenderer(),
    ) + undrawn

// The registry an application hands to `LocalKompotRegistry`: the toolkit's own renderers plus ours.
//
// Assembled here rather than at each call site, because two registries that differ by one entry are
// two clients, and the second one is discovered by a screen that draws a blank where the first drew
// a card.
fun konektRegistry(): KompotRegistry =
    KompotRegistry(
        kompotCoreRenderers + kompotStandardRenderers +
            // The form renderers, generated by the toolkit's own processor. In the registry
            // UNCONDITIONALLY rather than only on the screen that has a form: a registry that differs
            // between two screens is two clients, and the second is found by a form drawing five
            // unknown-component blocks where five fields should be.
            generatedFormsClientRenderers + konektRenderers,
    )
