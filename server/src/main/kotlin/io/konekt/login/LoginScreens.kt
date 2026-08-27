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
import io.github.youndie.kompot.standard.TextComponent
import io.konekt.components.BannerComponent
import io.konekt.components.MessageTones
import io.konekt.feature.auth.shared.api.LoginForms
import io.konekt.feature.auth.shared.api.LoginRefusals
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
    private fun refusalText(code: String?): String? =
        when (code) {
            LoginRefusals.WRONG_CODE -> "That code is wrong or has expired. Ask for a new one."

            // A word this build does not know draws NO banner rather than an empty one — the same rule
            // every open vocabulary here follows, and the reason a crafted link says nothing.
            else -> null
        }

    fun code(
        msisdn: String,
        error: String? = null,
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
                            refusalText(error)?.let { add(refusal("login-code-error", it)) }
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
