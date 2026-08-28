package io.konekt.login

import io.github.youndie.kompot.form.FormSchema
import io.github.youndie.kompot.form.standard.RequiredRule
import io.github.youndie.kompot.form.standard.TextFieldDefinition
import io.github.youndie.kompot.form.standard.TextValue
import io.github.youndie.kompot.forms.KompotFormResponse
import io.github.youndie.kompot.forms.ReadOnlyFieldComponent
import io.github.youndie.kompot.forms.SubmitFormAction
import io.github.youndie.kompot.forms.TextInputComponent
import io.github.youndie.kompot.material3.M3Colors
import io.github.youndie.kompot.material3.M3Typography
import io.github.youndie.kompot.standard.ButtonComponent
import io.github.youndie.kompot.standard.ColumnComponent
import io.github.youndie.kompot.standard.NavigateAction
import io.github.youndie.kompot.standard.TextComponent
import io.konekt.components.BannerComponent
import io.konekt.components.ButtonEmphasis
import io.konekt.components.MessageTones
import io.konekt.feature.auth.shared.api.LOGIN_DEEPLINK
import io.konekt.feature.auth.shared.api.LoginForms
import io.konekt.feature.auth.shared.api.LoginRefusals
import io.konekt.feature.auth.shared.api.ResendCodeAction
import io.konekt.screens.FILLS_THE_ROW

// THE WAY IN, BUILT BY THE SERVER LIKE EVERY OTHER SCREEN.
//
// Both runners used to sign in through `/api/v1/dev/otp` — the development endpoint that reads back a
// one-time code — and both said in their own comments what that is: *a machine endpoint revealing any
// subscriber's code IS the authentication system*. They did it anyway, because there was nowhere else
// to get a code.
//
// IT IS THE SHARPEST TEST OF THE WHOLE CLAIM. If a two-step form with a countdown and two refusals can
// be a server response, the boundary is where this product says it is. Nothing here is hand-written
// Compose, and the copy for a wrong code is a string the server owns — exactly like every other string.
object LoginScreens {
    // Aliases onto the shared constants rather than a second spelling: this object is where the
    // screens are BUILT, and the ids belong to the contract both sides read.
    const val NUMBER_FORM = LoginForms.NUMBER
    const val CODE_FORM = LoginForms.CODE

    const val MSISDN = LoginForms.FIELD_MSISDN
    const val CODE = LoginForms.FIELD_CODE

    fun number(error: String? = null): KompotFormResponse =
        KompotFormResponse(
            schema =
                FormSchema(
                    formId = NUMBER_FORM,
                    fields =
                        listOf(
                            TextFieldDefinition(
                                fieldId = MSISDN,
                                // VALIDATED ON THE CLIENT, which is what `form-core`'s split is for: an
                                // empty number is refused without a round trip. What is NOT validated
                                // here is the shape of a phone number — `Msisdn.parse` is the server's
                                // and stays the server's, because two spellings of "what a number is"
                                // is how one of them starts accepting something the other refuses.
                                rules = listOf(RequiredRule(errorMessage = "Enter your number")),
                            ),
                        ),
                ),
            screen =
                ColumnComponent(
                    id = "login",
                    spacing = 16,
                    children =
                        buildList {
                            add(
                                TextComponent(
                                    id = "login-title",
                                    text = "Sign in",
                                    style = M3Typography.HeadlineSmall,
                                    color = M3Colors.OnSurface,
                                ),
                            )
                            error?.let { add(refusal("login-error", it)) }
                            add(
                                TextInputComponent(
                                    id = "login-msisdn",
                                    fieldId = MSISDN,
                                    label = "Phone number",
                                    // AN EXAMPLE, AND NOT A MASK. `TextInputComponent` carries both
                                    // — `mask` was the obvious way to get the grouped shape the
                                    // canvas draws — and a mask here would REFUSE every number that
                                    // does not fit it. `Msisdn.parse` takes seven to fifteen digits
                                    // from any country on purpose, and a white-label product whose
                                    // sign-in field only accepts one country's numbers is broken for
                                    // exactly the operator who bought it.
                                    //
                                    // The example itself is deployment-specific in the same way the
                                    // currency is: it says which country this instance expects. It
                                    // is a hint rather than a constraint, so being wrong about it
                                    // costs a reader a moment instead of costing them the product.
                                    placeholder = "+7 999 120-45-67",
                                ),
                            )
                            add(
                                ButtonComponent(
                                    id = "login-submit",
                                    text = "Send me a code",
                                    action = SubmitFormAction(NUMBER_FORM),
                                    modifiers = FILLS_THE_ROW,
                                ),
                            )
                        },
                ),
        )

    // THE SENTENCE IS COMPOSED HERE, from a code the query carried. The text never travels in a URL:
    // it has spaces, and it would let anybody who can hand somebody a link put their own words on this
    // product's login screen.
    private fun refusalText(
        code: String?,
        retryInSeconds: Long,
    ): String? =
        when (code) {
            LoginRefusals.WRONG_CODE -> {
                "That code is wrong or has expired. Ask for a new one."
            }

            // THE NUMBER IS THE REFUSAL'S OWN, composed here like every other string. It is what
            // `RequestOtpUseCase` answered — the seconds it will keep refusing for — so the sentence
            // is true at the moment it is drawn rather than a countdown nobody confirmed.
            //
            // A number that arrived as zero or nonsense degrades to the sentence without one: a link
            // is something anybody can hand somebody, and the worst it can do here is drop a figure.
            LoginRefusals.TOO_SOON -> {
                if (retryInSeconds > 0) {
                    "A code was sent already. You can ask for another in $retryInSeconds seconds."
                } else {
                    "A code was sent already. Give it a moment before asking for another."
                }
            }

            // A word this build does not know draws NO banner rather than an empty one — the same rule
            // every open vocabulary here follows, and the reason a crafted link says nothing.
            else -> {
                null
            }
        }

    fun code(
        msisdn: String,
        error: String? = null,
        retryInSeconds: Long = 0,
        sent: Boolean = false,
    ): KompotFormResponse =
        KompotFormResponse(
            schema =
                FormSchema(
                    formId = CODE_FORM,
                    fields =
                        listOf(
                            // THE NUMBER TRAVELS AS A FIELD, seeded by the server and drawn by a bound
                            // `read_only_field`. Verifying needs both halves and this build keeps no
                            // session between the two steps — a server that remembered which number
                            // was asking would be a second place the answer lives.
                            TextFieldDefinition(
                                fieldId = MSISDN,
                                rules = emptyList(),
                                initialValue = TextValue(msisdn),
                            ),
                            TextFieldDefinition(
                                fieldId = CODE,
                                rules = listOf(RequiredRule(errorMessage = "Enter the code you were sent")),
                            ),
                        ),
                ),
            screen =
                ColumnComponent(
                    id = "login-code",
                    spacing = 16,
                    children =
                        buildList {
                            add(
                                TextComponent(
                                    id = "login-code-title",
                                    text = "Enter the code",
                                    style = M3Typography.HeadlineSmall,
                                    color = M3Colors.OnSurface,
                                ),
                            )
                            refusalText(error, retryInSeconds)?.let { add(refusal("login-code-error", it)) }

                            // SAID OUT LOUD, because a resend that changes nothing on the screen is
                            // indistinguishable from a button that does not work — and the code the
                            // subscriber is holding is now the wrong one, which they have no other way
                            // of learning.
                            if (sent) {
                                add(
                                    BannerComponent(
                                        id = "login-code-sent",
                                        // TRUE ON BOTH ARRIVALS. The first draft said "the one before
                                        // it no longer works", which is a claim about a code that did
                                        // not exist the first time this screen is reached — and the
                                        // endpoint cannot tell the two apart, because asking again IS
                                        // step one. Saying which one works is true either way and is
                                        // the half a subscriber acts on.
                                        text = "A code is on its way. Only the newest one works.",
                                        tone = MessageTones.INFO,
                                    ),
                                )
                            }
                            add(
                                ReadOnlyFieldComponent(
                                    id = "login-code-msisdn",
                                    fieldId = MSISDN,
                                    label = "Sent to",
                                    value = msisdn,
                                ),
                            )
                            add(
                                TextInputComponent(
                                    id = "login-code-input",
                                    fieldId = CODE,
                                    label = "Code",
                                ),
                            )
                            // ASK AGAIN, and until now there was no way to.
                            //
                            // A subscriber whose message never arrived had nothing to press and
                            // nowhere to go: this screen REPLACES the first one in the stack — the
                            // login submit answers a `navigate`, which is a step rather than a push —
                            // so there was no back control either. The one path out was closing the
                            // application.
                            //
                            // `SubmitFormAction(NUMBER_FORM)` and not a new endpoint: the number is
                            // already a field on THIS form, seeded and bound, so submitting these
                            // values under the first form's id posts exactly what step one posts. The
                            // client's `submits` map does the routing it already did.
                            add(
                                ButtonComponent(
                                    id = "login-code-resend",
                                    text = "Send a new code",
                                    // A VERB AND NOT A FORM SUBMIT, and the form submit was the first
                                    // attempt. The toolkit intercepts a `submit_form` only for the
                                    // form the SCREEN holds, so a button carrying the number form's
                                    // id from this screen fell through to the runner, which had no
                                    // handler — it posted nothing, which is the shape of the defect
                                    // it was added to fix. One OTP in the log where two were expected
                                    // is what said so.
                                    action = ResendCodeAction(msisdn),
                                    variant = ButtonEmphasis.QUIET,
                                    modifiers = FILLS_THE_ROW,
                                ),
                            )
                            // AND A WAY BACK, for the other half of the same hole: a number typed
                            // wrong cannot be corrected by asking the same number again.
                            add(
                                ButtonComponent(
                                    id = "login-code-restart",
                                    text = "Use a different number",
                                    action = NavigateAction(LOGIN_DEEPLINK),
                                    variant = ButtonEmphasis.QUIET,
                                    modifiers = FILLS_THE_ROW,
                                ),
                            )
                            add(
                                ButtonComponent(
                                    id = "login-code-submit",
                                    text = "Sign in",
                                    action = SubmitFormAction(CODE_FORM),
                                    modifiers = FILLS_THE_ROW,
                                ),
                            )
                        },
                ),
        )

    // A REFUSAL IS A BANNER AND NOT A FIELD ERROR, and the difference is whose fault it is. A field
    // error says "what you typed is the wrong shape", which the client can decide; a wrong or expired
    // code is a fact only the server knows, and putting it under the input would make the two look
    // like one kind of problem.
    private fun refusal(
        id: String,
        text: String,
    ) = BannerComponent(id = id, text = text, tone = MessageTones.ERROR)
}
