package io.konekt.e2e

import io.github.youndie.kompot.decodeKompotAction
import io.github.youndie.kompot.form.standard.AmountValue
import io.github.youndie.kompot.forms.FormPatchRequest
import io.github.youndie.kompot.forms.KompotFormResponse
import io.github.youndie.kompot.standard.NavigateAction
import io.github.youndie.kompot.standard.TextComponent
import io.konekt.components.OrderStatuses
import io.konekt.components.konektWalk
import io.konekt.feature.purchase.shared.api.TopUpForms
import io.konekt.feature.purchase.shared.api.TopUpResponse
import io.konekt.feature.purchase.shared.api.TopUpScreenResource
import io.konekt.feature.purchase.shared.api.TopUps
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.resources.get
import io.ktor.client.plugins.resources.post
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// TOPPING UP THE WAY A PERSON DOES IT, through the form, which nothing in this suite did.
//
// `TopUpScenarioTest` posts `CreateTopUpRequest(amountMinor)` — the DTO endpoint, whose unit is the
// domain's own and was never in doubt. The FORM is the only thing a subscriber can reach, and the
// number that arrives from it is the integer they typed and can see. The two met at one parameter
// called `amountMinor` and one of them was wrong by a hundred: typing 5000 credited $50, and typing
// 50 was refused by the screen that had just named $10 as the minimum (`B-67`).
//
// Every test below the wire was green throughout, because they all call the use case and all hand it
// minor units — the correct unit AT THAT BOUNDARY. The one nothing crossed is the boundary a person
// stands on.
class TopUpFormScenarioTest {
    // THE SCREEN'S OWN WORDS, and this is the assertion that pins the unit rather than repeating the
    // server's arithmetic.
    //
    // Whatever the limits line says the smallest top-up is, typing that number into the field beside
    // it must be accepted. A label in one unit over a field in another fails this by construction and
    // needs no constant here to compare against — which matters, because a test carrying its own copy
    // of `MIN_MINOR` would have agreed with the server about the number and still missed that the
    // field could not express it.
    @Test
    fun `the smallest amount the screen names is an amount the screen accepts`() =
        runBlocking {
            Stand.client().use { client ->
                val session = Stand.signIn(client)

                val minimum = client.statedMinimum(session)
                val accepted = client.topUpThroughForm(session, typed = minimum)

                assertEquals(
                    OrderStatuses.COMPLETED,
                    accepted.status,
                    "the screen names $minimum as its smallest top-up and then refused it: ${accepted.declineReason}",
                )

                // THE OTHER SIDE OF THE BOUNDARY, without which the test above passes on a server that
                // accepts everything — including the amounts the limits line exists to refuse.
                val refused = client.topUpThroughForm(session, typed = minimum - 1)
                assertEquals(
                    OrderStatuses.REJECTED,
                    refused.status,
                    "one below the stated minimum was taken, so the range on the screen means nothing",
                )
            }
        }

    // The two ways in must mean the same thing. Asserted on what the SERVER formatted for each rather
    // than on a number this test computes: the formatter is the one place that turns minor units into
    // something a person reads, so agreeing there is agreeing about the money.
    @Test
    fun `fifty typed into the form is the fifty the machine endpoint means`() =
        runBlocking {
            Stand.client().use { client ->
                val throughForm = Stand.client().use { it.topUpThroughForm(Stand.signIn(it), typed = 50) }
                val throughDto =
                    Stand.client().use { fresh ->
                        Stand.topUpRaw(fresh, Stand.signIn(fresh), amountMinor = 50 * 100)
                    }

                assertEquals(OrderStatuses.COMPLETED, throughForm.status)
                assertEquals(OrderStatuses.COMPLETED, throughDto.status)
                assertEquals(
                    throughDto.amountText,
                    throughForm.amountText,
                    "typing 50 into the form and asking the machine endpoint for $50 are not the same amount",
                )
                assertEquals(
                    throughDto.balanceText,
                    throughForm.balanceText,
                    "the balances differ, so one of the two paths moved the wrong amount",
                )
            }
        }

    // The number in "Between $10 and $50,000.", read off the served screen. Digits only, so whatever
    // the formatter does with separators and where it puts the symbol is its own business — this is
    // about the magnitude.
    private suspend fun HttpClient.statedMinimum(session: Stand.Session): Long {
        val form =
            Stand.json.decodeFromString(
                KompotFormResponse.serializer(),
                get(TopUpScreenResource()) { bearerAuth(session.accessToken) }.bodyAsText(),
            )

        val limits =
            form.screen
                .konektWalk()
                .filterIsInstance<TextComponent>()
                .firstOrNull { it.id == "top-up-limits" }
        assertNotNull(limits, "the top-up screen states no limits, so a subscriber learns them by being refused")

        val first = Regex("""\d[\d\s,.  ]*""").find(limits.text)
        assertNotNull(first, "no number in the limits line: ${limits.text}")
        val magnitude = first.value.filter { it.isDigit() }.toLong()
        assertTrue(magnitude > 0, "the stated minimum reads as zero: ${limits.text}")
        return magnitude
    }

    // Posted the way the client posts it: the form's values, and the answer is a `navigate` naming
    // where the result lives. The result is then FETCHED, because that is what the client does with a
    // navigate — see `EsimInstallScenarioTest` for what asserting on a discarded response body costs.
    private suspend fun HttpClient.topUpThroughForm(
        session: Stand.Session,
        typed: Long,
    ): TopUpResponse {
        val answer =
            post(TopUpScreenResource()) {
                bearerAuth(session.accessToken)
                contentType(ContentType.Application.Json)
                setBody(
                    Stand.json.encodeToString(
                        FormPatchRequest.serializer(),
                        FormPatchRequest(
                            formId = TopUpForms.AMOUNT_FORM,
                            fieldId = TopUpForms.FIELD_AMOUNT,
                            // What kompot's amount input sends: the integer it is displaying, plus the
                            // currency it was told to draw beside it.
                            values = mapOf(TopUpForms.FIELD_AMOUNT to AmountValue(typed, "USD")),
                        ),
                    ),
                )
            }

        val navigate = Stand.json.decodeKompotAction(answer.bodyAsText())
        assertTrue(navigate is NavigateAction, "the form answered something other than a navigate: $navigate")
        val topUpId = navigate.deeplink.substringAfterLast('/')

        return get(TopUps.ById(topUpId = topUpId)) { bearerAuth(session.accessToken) }.body()
    }
}
