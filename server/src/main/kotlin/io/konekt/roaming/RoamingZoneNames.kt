package io.konekt.roaming

import io.konekt.feature.roaming.server.domain.Zones

// A zone code as a person reads it.
//
// IN `:server` AND NOT IN THE DOMAIN, deliberately. The domain knows a zone is a string it must not
// interpret; what "tr" is called on a screen is copy, and copy belongs where the screens are built.
// It is also why the fallback is the code itself rather than an exception: a zone added to the
// catalogue and not to this map must produce a card that says "us" and works, not a 500.
object RoamingZoneNames {
    private val names =
        mapOf(
            "tr" to "Turkey",
            "eu" to "Europe",
            "us" to "United States",
            Zones.HOME to "Home",
        )

    fun of(zone: String): String = names[zone] ?: zone
}
