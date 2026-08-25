package io.konekt.feature.purchase.server.data

import io.konekt.domain.Currency
import io.konekt.domain.Money
import io.konekt.feature.purchase.server.domain.Plan
import io.konekt.feature.purchase.server.domain.PlanCatalog

// The catalogue, in memory.
//
// The BSS is outside this system's boundary, so there is nothing real to ask. A table with a seed
// migration would look more finished and would be the same fiction with a schema around it — and it
// would have to be maintained by the first person who wanted a fourth plan. The real catalogue, with
// prices that move and a zone per plan, is B-19's.
//
// The sold-out plan is not padding: the canvas draws that row, and a purchase interceptor refuses on
// it, so it is the fixture that makes the refusal path testable at all.
class StaticPlanCatalog(
    private val plans: List<Plan> = DEFAULT,
) : PlanCatalog {
    override suspend fun find(planId: String): Plan? = plans.firstOrNull { it.id == planId }

    companion object {
        val DEFAULT =
            listOf(
                Plan("tr-10gb-30d", "Turkey · 10 GB · 30 days", Money.ofMajor(12, Currency.DEFAULT), onSale = true),
                Plan("eu-5gb-14d", "Europe · 5 GB · 14 days", Money.ofMajor(9, Currency.DEFAULT), onSale = true),
                Plan(
                    "us-20gb-30d",
                    "United States · 20 GB · 30 days",
                    Money.ofMajor(24, Currency.DEFAULT),
                    onSale = false,
                ),
            )
    }
}
