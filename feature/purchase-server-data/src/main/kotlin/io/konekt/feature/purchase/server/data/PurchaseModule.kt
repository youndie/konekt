package io.konekt.feature.purchase.server.data

import io.konekt.feature.purchase.server.domain.AccountBalances
import io.konekt.feature.purchase.server.domain.AnnouncePurchaseInterceptor
import io.konekt.feature.purchase.server.domain.ConfirmPurchaseUseCase
import io.konekt.feature.purchase.server.domain.DEFAULT_CONFIRMATION_TTL
import io.konekt.feature.purchase.server.domain.Entitlements
import io.konekt.feature.purchase.server.domain.FindOrderUseCase
import io.konekt.feature.purchase.server.domain.FindTopUpUseCase
import io.konekt.feature.purchase.server.domain.HistoryRepository
import io.konekt.feature.purchase.server.domain.HoldFundsInterceptor
import io.konekt.feature.purchase.server.domain.LoadHistoryUseCase
import io.konekt.feature.purchase.server.domain.LoadOrderScreenUseCase
import io.konekt.feature.purchase.server.domain.PURCHASE_SAGA_TYPE
import io.konekt.feature.purchase.server.domain.PaymentGateway
import io.konekt.feature.purchase.server.domain.PlanCatalog
import io.konekt.feature.purchase.server.domain.ProvisionInterceptor
import io.konekt.feature.purchase.server.domain.PurchaseEvents
import io.konekt.feature.purchase.server.domain.StartPurchaseUseCase
import io.konekt.feature.purchase.server.domain.StartTopUpUseCase
import io.konekt.feature.purchase.server.domain.TOP_UP_SAGA_TYPE
import io.konekt.feature.purchase.server.domain.ValidatePurchaseInterceptor
import io.konekt.feature.roaming.server.domain.RoamingPackages
import io.konekt.feature.usage.server.domain.UsageGrants
import io.konekt.time.KonektClock
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.jdbc.Database
import org.koin.core.qualifier.named
import org.koin.dsl.module
import ru.workinprogress.petich.PetichInterceptor
import kotlin.time.Duration

// The four steps, in one place, so the saga can be read as a list rather than assembled from
// annotations. Order inside a phase is priority; between phases it is the engine's fixed order.
fun purchaseInterceptors(
    balances: AccountBalances,
    entitlements: Entitlements,
    plans: PlanCatalog,
    payments: PaymentGateway,
    grants: UsageGrants,
    roaming: RoamingPackages,
    clock: KonektClock,
    json: Json,
    confirmationTtl: Duration = DEFAULT_CONFIRMATION_TTL,
): List<PetichInterceptor<*>> {
    val events = PurchaseEvents(json)
    return listOf(
        ValidatePurchaseInterceptor(plans, balances),
        HoldFundsInterceptor(balances, entitlements, events, confirmationTtl),
        ProvisionInterceptor(balances, entitlements, payments, grants, roaming, clock),
        AnnouncePurchaseInterceptor(events),
    )
}

// The engine is NOT built here. It is built by the composition root, because an application usually
// keeps several — one per saga type, sharing one saga table — and only the root knows the set. A
// feature that built its own would be a feature deciding how many there are.
fun purchaseModule(
    database: Database,
    // THE CATALOGUE AS A PARAMETER, defaulted to the static one. `B-87` needed the composition root
    // to wrap it — a custom package is a plan the catalogue did not write down, and resolving one
    // needs a tariff function that lives in `:server` — and the alternative was a second
    // `single<PlanCatalog>` in the root overriding this one. Two bindings for one type resolve to
    // whichever Koin saw last, which is the failure the qualifier on the engines below exists for.
    catalogue: PlanCatalog = StaticPlanCatalog(),
    paymentMode: MockPaymentGateway.Mode = MockPaymentGateway.Mode.APPROVE,
    paymentDelay: Duration = Duration.ZERO,
) = module {
    single<AccountBalances> { ExposedAccountBalances(database, get()) }
    single<Entitlements> { ExposedEntitlements(database, get()) }
    single { catalogue }
    single<PaymentGateway> { MockPaymentGateway(mode = paymentMode, delay = paymentDelay) }

    // Explicit lambdas rather than singleOf/factoryOf: the reflective form resolves every
    // constructor parameter through the container, including defaulted ones, and both the
    // interceptor list and the use cases have those.
    // THE ENGINE IS ASKED FOR BY SAGA TYPE, and the qualifier is not tidiness. There are two engines
    // over one saga table, and an unqualified `get()` would resolve whichever binding Koin saw last —
    // a purchase driven by the top-up interceptor list finds no step that supports its payload,
    // completes having done nothing, and answers COMPLETED for an order that was never paid for.
    factory { StartPurchaseUseCase(get(named(PURCHASE_SAGA_TYPE)), get(), get(), get()) }
    factory { ConfirmPurchaseUseCase(get(named(PURCHASE_SAGA_TYPE)), get(), get()) }
    factory { FindOrderUseCase(get(), get()) }

    // Putting money in. The engine is the top-up one for the same reason.
    factory { StartTopUpUseCase(get(named(TOP_UP_SAGA_TYPE)), get(), get()) }
    factory { FindTopUpUseCase(get(), get()) }
    // Both were injected by `purchaseRoutes` and bound by nothing, so the history screen and the
    // order screen answered 500 in the running server. Every route test builds its own graph and
    // supplies what it needs, which is why 191 green tests said nothing about it — a stand was the
    // first thing to ask the application.
    single<HistoryRepository> { ExposedHistoryRepository(database) }
    factory { LoadHistoryUseCase(get()) }
    factory { LoadOrderScreenUseCase(get(), get()) }
}
