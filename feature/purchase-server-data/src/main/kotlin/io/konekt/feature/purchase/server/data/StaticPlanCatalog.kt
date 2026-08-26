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
        private const val MB_PER_GB = 1_024L

        val DEFAULT =
            listOf(
                // THE ONLY HOME PLAN, and the catalogue would be broken without it. Every other entry
                // here is a roaming package, and roaming packages are provisioned dormant — so a
                // catalogue of nothing but roaming plans is a product in which no `usage_counter` is
                // ever created, the traffic simulator has nobody to tick, and the home screen is
                // permanently empty. That was the state of this file for the length of one commit.
                //
                // It is also what an MVNO actually sells: a bundle for where you live, and packages
                // for where you go.
                Plan(
                    "home-20gb-30d",
                    "Home · 20 GB · 30 days",
                    Money.ofMajor(15, Currency.DEFAULT),
                    onSale = true,
                    dataMb = 20 * MB_PER_GB,
                    // No zone and no validity: a home allowance has no start, so it has no expiry
                    // dated from one. The defaults say exactly that.
                ),
                Plan(
                    "tr-10gb-30d",
                    "Turkey · 10 GB · 30 days",
                    Money.ofMajor(12, Currency.DEFAULT),
                    onSale = true,
                    dataMb = 10 * MB_PER_GB,
                    zone = "tr",
                    validForDays = 30,
                ),
                Plan(
                    "eu-5gb-14d",
                    "Europe · 5 GB · 14 days",
                    Money.ofMajor(9, Currency.DEFAULT),
                    onSale = true,
                    dataMb = 5 * MB_PER_GB,
                    zone = "eu",
                    validForDays = 14,
                ),
                Plan(
                    "us-20gb-30d",
                    "United States · 20 GB · 30 days",
                    Money.ofMajor(24, Currency.DEFAULT),
                    onSale = false,
                    dataMb = 20 * MB_PER_GB,
                    zone = "us",
                    validForDays = 30,
                ),
            )
    }
}
