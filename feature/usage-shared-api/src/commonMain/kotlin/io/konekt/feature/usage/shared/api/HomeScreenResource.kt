package io.konekt.feature.usage.shared.api

import io.ktor.resources.Resource

// The home screen's path.
//
// It lives with the usage feature rather than in a module of its own because the screen's subject is
// the counters — the balance is one line on it, composed in by the composition root, which is the
// only place that can see both features. The alternative, a `feature/home-*` vertical with no domain
// and no data of its own, would be three modules to hold one string.
@Resource("/api/v1/screens/home")
class HomeScreenResource
