package io.konekt.feature.roaming.server.data

import io.konekt.feature.roaming.server.domain.RoamingPackages
import org.jetbrains.exposed.v1.jdbc.Database
import org.koin.dsl.module

// The roaming feature's bindings.
//
// A MODULE OF ONE, and it exists anyway rather than a line in the composition root, for the reason
// B-07 paid to learn: a feature whose repository is constructed in `Application.kt` is a feature that
// looks wired from inside every test — each of which builds what it needs by hand — and is wired
// nowhere. `KoinGraphTest` resolves what the application installs, and it can only do that for
// something the application installs by name.
//
// The card builder is NOT here. It lives in `:server` because what a zone is called on screen is copy,
// and copy belongs where the screens are composed.
fun roamingModule(database: Database) =
    module {
        single<RoamingPackages> { ExposedRoamingPackages(database, get()) }
    }
