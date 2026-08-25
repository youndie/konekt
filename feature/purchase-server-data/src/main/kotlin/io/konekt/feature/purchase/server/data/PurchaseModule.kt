package io.konekt.feature.purchase.server.data

import io.konekt.feature.purchase.server.domain.AccountBalances
import io.konekt.feature.purchase.server.domain.AnnouncePurchaseInterceptor
import io.konekt.feature.purchase.server.domain.ConfirmPurchaseUseCase
import io.konekt.feature.purchase.server.domain.DEFAULT_CONFIRMATION_TTL
import io.konekt.feature.purchase.server.domain.Entitlements
import io.konekt.feature.purchase.server.domain.FindOrderUseCase
import io.konekt.feature.purchase.server.domain.HoldFundsInterceptor
import io.konekt.feature.purchase.server.domain.PaymentGateway
import io.konekt.feature.purchase.server.domain.PlanCatalog
import io.konekt.feature.purchase.server.domain.ProvisionInterceptor
import io.konekt.feature.purchase.server.domain.PurchaseEvents
import io.konekt.feature.purchase.server.domain.StartPurchaseUseCase
import io.konekt.feature.purchase.server.domain.ValidatePurchaseInterceptor
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.jdbc.Database
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
    json: Json,
    confirmationTtl: Duration = DEFAULT_CONFIRMATION_TTL,
): List<PetichInterceptor<*>> {
    val events = PurchaseEvents(json)
    return listOf(
        ValidatePurchaseInterceptor(plans, balances),
        HoldFundsInterceptor(balances, entitlements, events, confirmationTtl),
        ProvisionInterceptor(balances, entitlements, payments),
        AnnouncePurchaseInterceptor(events),
    )
}

// The engine is NOT built here. It is built by the composition root, because an application usually
// keeps several — one per saga type, sharing one saga table — and only the root knows the set. A
// feature that built its own would be a feature deciding how many there are.
fun purchaseModule(
    database: Database,
    paymentMode: MockPaymentGateway.Mode = MockPaymentGateway.Mode.APPROVE,
    paymentDelay: Duration = Duration.ZERO,
) = module {
    single<AccountBalances> { ExposedAccountBalances(database, get()) }
    single<Entitlements> { ExposedEntitlements(database, get()) }
    single<PlanCatalog> { StaticPlanCatalog() }
    single<PaymentGateway> { MockPaymentGateway(mode = paymentMode, delay = paymentDelay) }

    // Explicit lambdas rather than singleOf/factoryOf: the reflective form resolves every
    // constructor parameter through the container, including defaulted ones, and both the
    // interceptor list and the use cases have those.
    factory { StartPurchaseUseCase(get(), get(), get(), get()) }
    factory { ConfirmPurchaseUseCase(get(), get(), get()) }
    factory { FindOrderUseCase(get(), get()) }
}
