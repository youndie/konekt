package io.konekt.feature.usage.server.data

import io.konekt.feature.usage.server.domain.ConsumeUsageUseCase
import io.konekt.feature.usage.server.domain.LoadCountersUseCase
import io.konekt.feature.usage.server.domain.UsageAddOns
import io.konekt.feature.usage.server.domain.UsageCounters
import io.konekt.feature.usage.server.domain.UsageGrants
import org.jetbrains.exposed.v1.jdbc.Database
import org.koin.dsl.module

// The usage feature's bindings — and until B-07 there were none.
//
// Everything below existed, was tested, and was reachable from nothing: the counters were never in
// the graph, `LoadCountersUseCase` was never constructed, and a completed purchase granted no
// allowance. Every test passed, because each one built what it needed by hand. What nothing checked
// was that the application does.
//
// ONE INSTANCE BEHIND TWO INTERFACES. `ExposedUsageCounters` implements both `UsageCounters` and
// `UsageGrants`, and two `single { }` blocks would build two of it — harmless today and exactly the
// sort of thing that stops being harmless when one of them caches.
fun usageModule(database: Database) =
    module {
        single { ExposedUsageCounters(database, get()) }
        single<UsageCounters> { get<ExposedUsageCounters>() }
        single<UsageGrants> { get<ExposedUsageCounters>() }

        single<UsageAddOns> { StaticUsageAddOns() }
        single { UsageCounterCards(get()) }

        factory { LoadCountersUseCase(get()) }
        factory { ConsumeUsageUseCase(get()) }
    }
