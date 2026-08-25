package io.konekt.time

import org.koin.dsl.module

// The composition root's half of the clock. A single binding, so replacing time in a test is one
// override rather than a search.
val timeModule =
    module {
        single<KonektClock> { SystemClock }
    }
