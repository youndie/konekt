package io.konekt.client.app

import io.github.youndie.kompot.KompotAction
import io.github.youndie.kompot.decodeKompotAction
import io.github.youndie.kompot.form.standard.TextValue
import io.github.youndie.kompot.forms.FormPatchRequest
import io.github.youndie.kompot.standard.NavigateAction
import io.konekt.feature.auth.shared.api.LoginForms
import io.konekt.feature.auth.shared.api.LoginSubmit
import io.konekt.feature.auth.shared.api.ResendCodeAction
import io.ktor.client.HttpClient
import io.ktor.client.plugins.resources.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json

// ASKING FOR ANOTHER CODE, handled by the runner like every other verb this application has.
//
// A subscriber whose message never arrived had nothing to press: the code screen REPLACES the login
// screen — the submit answers a `navigate`, which is a step rather than a push — so there was no back
// control either, and the only path out was closing the application (`B-50`).
//
// IT POSTS TO THE SAME ENDPOINT STEP ONE POSTS TO, because asking again IS step one: the number is
// what `LoginSubmit` wants and the answer is the same `navigate` back to the code screen, carrying
// either "a new code is on its way" or the refusal with the seconds left on it. Nothing new is served
// and no second way to request a code exists.
class ResendCode(
    private val http: HttpClient,
    private val json: Json,
    // WHERE THE ANSWER'S DEEPLINK RESOLVES. The endpoint answers a `navigate` — a deeplink, not an
    // address — and the runner owes the holder an address. The login screens are exactly what the
    // bootstrap is for, so resolving here costs nothing and keeps the composed query the server's.
    private val routes: Map<String, String> = KonektRoutes.bootstrap,
) {
    suspend fun addressFor(action: KompotAction): String? {
        if (action !is ResendCodeAction) return null

        val answer =
            json.decodeKompotAction(
                http
                    .post(LoginSubmit()) {
                        contentType(ContentType.Application.Json)
                        setBody(
                            json.encodeToString(
                                FormPatchRequest.serializer(),
                                // The shape a submit sends, built here because there is no form on
                                // screen to build it: `fieldId` is what CHANGED and nothing did, so it
                                // carries the form's own id — the same convention the toolkit's own
                                // submit follows.
                                FormPatchRequest(
                                    formId = LoginForms.NUMBER,
                                    fieldId = LoginForms.NUMBER,
                                    values = mapOf(LoginForms.FIELD_MSISDN to TextValue(action.msisdn)),
                                ),
                            ),
                        )
                    }.bodyAsText(),
            )

        // A `navigate` is the only thing this endpoint answers — a refusal included, which is the
        // point: asking too soon is a screen rather than a status code. Anything else is a contract
        // that moved, and returning null for it would make it look like a button with no handler.
        val destination = (answer as? NavigateAction)?.deeplink ?: error("resending answered $answer")
        return resolve(destination, routes)
    }
}
