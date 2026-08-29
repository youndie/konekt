package io.konekt.feature.roaming.shared.api

import io.ktor.resources.Resource

// ROAMING ON THE WIRE, WHICH IT WAS NOT AT ALL UNTIL `B-88`.
//
// `B-19` built the domain, the table, the provisioning and the dormant rule, and the vertical stayed
// server-only: there was no `-shared-api` module, so nothing about roaming was a contract the client
// knew. Packages appeared as cards on the home screen and there was no place that answered the
// question a subscriber going abroad actually has — *what do I have for this trip*.
//
// ONE ADDRESS AND NO ACTION. Every other feature here puts a verb on the wire; this one does not, and
// the absence is the feature: a roaming package is started by using it, and there is no press that
// starts one. A `StartRoamingAction` would be exactly the development route this item deleted, moved
// into the client.
@Resource("/api/v1/screens/roaming")
class RoamingScreenResource

// `app://roaming` — reached from the home screen, where the packages themselves are.
const val ROAMING_DEEPLINK: String = "app://roaming"
