package io.konekt.login

import io.github.youndie.kompot.auth.UpdateSessionAction
import io.github.youndie.kompot.form.FieldValue
import io.github.youndie.kompot.forms.FormPatchRequest
import io.github.youndie.kompot.ktor.respondKompotAction
import io.github.youndie.kompot.standard.NavigateAction
import io.konekt.domain.KonektException
import io.konekt.feature.auth.server.domain.RequestOtpUseCase
import io.konekt.feature.auth.server.domain.VerifyOtpUseCase
import io.konekt.feature.auth.shared.api.LOGIN_CODE_DEEPLINK
import io.konekt.feature.auth.shared.api.LoginCodeScreenResource
import io.konekt.feature.auth.shared.api.LoginCodeSubmit
import io.konekt.feature.auth.shared.api.LoginRefusals
import io.konekt.feature.auth.shared.api.LoginScreenResource
import io.konekt.feature.auth.shared.api.LoginSubmit
import io.ktor.http.ContentType
import io.ktor.http.encodeURLParameter
import io.ktor.server.request.receiveText
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import kotlinx.serialization.json.Json
import org.koin.ktor.ext.inject

// AUTH TIER: public, and it has to be — these are the way in. What protects them is the lockout in the
// use cases and the fact that neither answer depends on whether the number is known, which is the same
// pair that protects the OTP routes beside them.
//
// THESE ROUTES ADD NO NEW POWER. Everything they do, `AuthOtp.Request` and `AuthOtp.Verify` already
// did; what they add is the SHAPE a form can talk to — a `submit` endpoint taking a form's values and
// answering an action. The old pair stays because a client that is not this one may want a DTO.
fun Route.loginRoutes() {
    val requestOtp by inject<RequestOtpUseCase>()
    val verifyOtp by inject<VerifyOtpUseCase>()
    val json by inject<Json>()

    get<LoginScreenResource> {
        call.respondForm(json, LoginScreens.number())
    }

    get<LoginCodeScreenResource> { params ->
        // NO NUMBER MEANS STEP ONE. A code screen that cannot say who it is for is a form whose submit
        // would be refused for a reason the subscriber cannot see, so it answers the first step
        // instead — the same screen they would have got by opening the application.
        if (params.msisdn.isBlank()) {
            call.respondForm(json, LoginScreens.number())
            return@get
        }
        call.respondForm(json, LoginScreens.code(params.msisdn, params.error))
    }

    post<LoginSubmit> {
        val values = call.formValues(json)
        val msisdn =
            values.text(LoginScreens.MSISDN) ?: throw KonektException.Validation(LoginScreens.MSISDN, "no number")

        // `.getOrThrow()`, and nothing else: StatusPages maps every refusal this can produce, and a
        // malformed number is a 422 the client shows rather than a screen the server rebuilds.
        requestOtp(msisdn).getOrThrow()

        // A `navigate` and not the next screen's tree. The endpoint's job is to say WHERE, and the
        // client fetches — which keeps one shape for arriving at a screen instead of two.
        // ENCODED, because a number can carry a `+` and an unencoded one arrives as a space.
        call.respondKompotAction(
            json,
            NavigateAction("$LOGIN_CODE_DEEPLINK?msisdn=${msisdn.encodeURLParameter()}"),
        )
    }

    post<LoginCodeSubmit> {
        val values = call.formValues(json)
        val msisdn =
            values.text(LoginScreens.MSISDN) ?: throw KonektException.Validation(LoginScreens.MSISDN, "no number")
        val code = values.text(LoginScreens.CODE) ?: throw KonektException.Validation(LoginScreens.CODE, "no code")

        val session =
            verifyOtp(VerifyOtpUseCase.Params(msisdn, code)).getOrNull()
                // A WRONG OR EXPIRED CODE IS AN ANSWER, not a failure, and the difference decides what
                // the subscriber sees. Throwing would give them a 422 and a screen that did not change;
                // sending them back to the same screen with the reason on it is what the acceptance
                // asks for — and the reason is composed HERE, like every other string.
                ?: return@post call.respondKompotAction(
                    json,
                    // A CODE IN THE QUERY AND NOT A SENTENCE. The sentence has spaces — which made the
                    // request line malformed and the client report "Unsupported HTTP version" — and a
                    // link is something anybody can hand somebody, so text in it is text on this
                    // product's login screen. `LoginScreens` turns the word into copy.
                    NavigateAction(
                        "$LOGIN_CODE_DEEPLINK?msisdn=${msisdn.encodeURLParameter()}" +
                            "&error=${LoginRefusals.WRONG_CODE}",
                    ),
                )

        call.respondKompotAction(
            json,
            UpdateSessionAction(accessToken = session.accessToken, refreshToken = session.refreshToken),
        )
    }
}

// The form's values as the client posts them — `FormPatchRequest` is the shape a `submit_form` sends,
// the same one a patch does. Read as text rather than through ContentNegotiation for the reason every
// kompot body here is: the polymorphic scope that decodes a `FieldValue` is the application's `Json`.
private suspend fun io.ktor.server.application.ApplicationCall.formValues(json: Json): Map<String, FieldValue> =
    json.decodeFromString(FormPatchRequest.serializer(), receiveText()).values

private fun Map<String, FieldValue>.text(fieldId: String): String? =
    this[fieldId]?.plainValue?.takeIf {
        it.isNotBlank()
    }

// respondKompotComponent's equivalent for a form, and it exists for the same reason: a plain respond
// resolves the serialiser from the concrete class and drops the discriminator on every component in
// the tree it carries.
private suspend fun io.ktor.server.application.ApplicationCall.respondForm(
    json: Json,
    form: io.github.youndie.kompot.forms.KompotFormResponse,
) = respondText(
    json.encodeToString(
        io.github.youndie.kompot.forms.KompotFormResponse
            .serializer(),
        form,
    ),
    ContentType.Application.Json,
)
