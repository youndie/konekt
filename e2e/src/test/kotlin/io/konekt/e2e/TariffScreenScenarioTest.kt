package io.konekt.e2e

import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.decodeKompotComponent
import io.github.youndie.kompot.standard.ButtonComponent
import io.github.youndie.kompot.standard.NavigateAction
import io.github.youndie.kompot.standard.TextComponent
import io.konekt.components.BannerComponent
import io.konekt.components.PlanCardComponent
import io.konekt.components.konektWalk
import io.konekt.feature.shell.shared.api.ProfileScreenResource
import io.konekt.feature.tariff.shared.api.ChangeTariffAction
import io.konekt.feature.tariff.shared.api.ChangeTariffRequest
import io.konekt.feature.tariff.shared.api.ConfirmTariffChangeAction
import io.konekt.feature.tariff.shared.api.TARIFFS_DEEPLINK
import io.konekt.feature.tariff.shared.api.TariffChangeResponse
import io.konekt.feature.tariff.shared.api.TariffChangeScreenResource
import io.konekt.feature.tariff.shared.api.TariffChanges
import io.konekt.feature.tariff.shared.api.TariffsScreenResource
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.resources.get
import io.ktor.client.plugins.resources.post
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// CHANGING TARIFF, WALKED THE WAY A SUBSCRIBER WALKS IT — profile, catalogue, change, confirmation —
// against a running stand.
//
// `B-21` built this vertical and `B-86` found it had no way in: no component sent a
// `ChangeTariffRequest`, `:client` did not depend on the contract, and three tariffs sat in a
// catalogue nothing displayed. `TariffChangeScenarioTest` beside this file covers the SAGA and would
// have stayed green through all of that, because it posts to the routes directly. This one refuses to:
// every step here starts from something a screen actually offered.
class TariffScreenScenarioTest {
    @Test
    fun `a subscriber reaches the catalogue from their profile, changes tariff and confirms it`() =
        runBlocking {
            Stand.client().use { client ->
                val session = Stand.signIn(client)

                // THE WAY IN, and its absence is the item. The profile has to name the tariff and
                // offer the catalogue; without both the rest of this walk is unreachable in the app
                // however well the routes work.
                val profile = client.screen(ProfileScreenResource(), session.accessToken)
                val tariffOnProfile =
                    profile
                        .konektWalk()
                        .filterIsInstance<TextComponent>()
                        .singleOrNull { it.id == "profile-tariff" }
                assertNotNull(tariffOnProfile, "the profile screen does not say which tariff the line is on")

                val toCatalogue =
                    profile
                        .konektWalk()
                        .filterIsInstance<ButtonComponent>()
                        .single { it.id == "profile-tariff-change" }
                assertEquals(
                    TARIFFS_DEEPLINK,
                    (toCatalogue.action as NavigateAction).deeplink,
                    "the profile offers no way to the tariff catalogue",
                )

                // THE CATALOGUE. What it offers is what the walk presses — the id is never typed here,
                // because a test that types one is a test that would pass over a screen offering
                // nothing.
                val catalogue = client.screen(TariffsScreenResource(), session.accessToken)
                val offered =
                    catalogue
                        .konektWalk()
                        .filterIsInstance<PlanCardComponent>()
                        .mapNotNull { it.action as? ChangeTariffAction }
                assertTrue(offered.isNotEmpty(), "the catalogue offers no tariff to change to")

                val current = catalogue.konektWalk().filterIsInstance<PlanCardComponent>().single { it.action == null }
                assertEquals("Your tariff", current.badgeText, "the tariff they are on is not marked")

                // THE CHANGE, from the action the card carried.
                val started: TariffChangeResponse =
                    client
                        .post(TariffChanges()) {
                            bearerAuth(session.accessToken)
                            setBody(ChangeTariffRequest(offered.first().tariffId))
                        }.body()

                // AND THE SCREEN FOR IT, fetched as the client fetches it: the handler posts, takes
                // the id out of the answer and refetches the address. Asserting on the POST's body
                // would be asserting on a payload nothing renders — the rule `B-66` cost.
                val waiting = client.screen(TariffChangeScreenResource(started.changeId), session.accessToken)
                val confirm =
                    waiting
                        .konektWalk()
                        .filterIsInstance<ButtonComponent>()
                        .single { it.id == "tariff-change-confirm" }
                assertEquals(started.changeId, (confirm.action as ConfirmTariffChangeAction).changeId)

                // The catalogue now withdraws every offer and points back at the waiting change, which
                // is the state the server would refuse a second change in.
                val duringChange = client.screen(TariffsScreenResource(), session.accessToken)
                assertTrue(
                    duringChange.konektWalk().filterIsInstance<PlanCardComponent>().all { it.action == null },
                    "a tariff is still offered while a change is waiting, and a press would be refused",
                )
                assertNotNull(
                    duringChange
                        .konektWalk()
                        .filterIsInstance<BannerComponent>()
                        .singleOrNull { it.id == "tariffs-pending" },
                    "the catalogue does not say a change is waiting, so the confirmation is unreachable",
                )

                // CONFIRMING, from the action the button carried.
                client.post(TariffChanges.ById.Confirm(TariffChanges.ById(changeId = started.changeId))) {
                    bearerAuth(session.accessToken)
                }

                val decided = client.screen(TariffChangeScreenResource(started.changeId), session.accessToken)
                assertTrue(
                    decided.konektWalk().filterIsInstance<ButtonComponent>().isEmpty(),
                    "the change screen still offers a confirmation after the change was confirmed",
                )
                // The one thing this feature exists to show: both tariffs are true until the boundary,
                // so the screen still names the tariff they are on as well as the one they moved to.
                val texts =
                    decided
                        .konektWalk()
                        .filterIsInstance<TextComponent>()
                        .map { it.text }
                assertTrue(
                    texts.any { it == tariffOnProfile.text },
                    "the confirmed change no longer names the tariff still current until the boundary: $texts",
                )
            }
        }

    private suspend inline fun <reified T : Any> HttpClient.screen(
        resource: T,
        token: String,
    ): KompotComponent =
        Stand.json.decodeKompotComponent(
            get(resource) { bearerAuth(token) }.bodyAsText(),
        )
}
