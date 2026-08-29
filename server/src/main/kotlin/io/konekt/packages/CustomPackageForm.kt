package io.konekt.packages

import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.form.FormPatch
import io.github.youndie.kompot.form.FormSchema
import io.github.youndie.kompot.form.standard.EntityValue
import io.github.youndie.kompot.form.standard.SelectionFieldDefinition
import io.github.youndie.kompot.form.standard.TextFieldDefinition
import io.github.youndie.kompot.form.standard.TextValue
import io.github.youndie.kompot.forms.KompotFormResponse
import io.github.youndie.kompot.forms.ReadOnlyFieldComponent
import io.github.youndie.kompot.forms.SelectInputComponent
import io.github.youndie.kompot.forms.SelectOption
import io.github.youndie.kompot.forms.SubmitFormAction
import io.github.youndie.kompot.standard.ButtonComponent
import io.github.youndie.kompot.standard.ColumnComponent
import io.github.youndie.kompot.standard.TextComponent
import io.konekt.domain.Money
import io.konekt.feature.packages.shared.api.CustomPackageFields
import io.konekt.money.MoneyFormat
import io.konekt.screens.FILLS_THE_ROW

// THE ONE SCREEN WHERE form-core'S SPLIT EARNS ITS KEEP: the steps a subscriber may choose are
// validated on the client, and the price is a patch from the server — because a price computed on the
// client is a price a client can argue with.
object CustomPackageForm {
    // Three quantities as SELECTION fields, and that is the wire's shape rather than the design's.
    // kompot's standard field set is text, amount, checkbox, autocomplete and selection: there is no
    // slider and no numeric range, so a quantity is a choice from a list. That suits a tariff, which
    // sells packages rather than arbitrary numbers — and the steps are the same list the server
    // prices, so a value outside it arrived from something that is not this form.
    //
    // `triggersPatch = true` on all three: any of them moving changes the price, which is the only
    // reason this form talks to the server between opening and submitting.
    // FIVE FIELDS: three a subscriber chooses and two the server computes.
    //
    // The computed pair were fields once before, briefly, and the conformance kit threw them out:
    //
    //     [form-fields] field "balance" is declared but never rendered
    //     [form-fields] field "price" is declared but never rendered
    //
    // It was right. SPEC §9.2 asks that every declared fieldId have a component rendering it, and at
    // the time the only non-editable display — `read_only_field` — was not bound to the controller,
    // so a computed value could be declared or displayed and not both. Filed as youndie/kompot#89,
    // fixed in 0.33.0: `read_only_field` takes an optional `fieldId` and is then bound for values and
    // for visibility, editable by nobody. Both are now declared AND rendered, which is what makes a
    // patch able to reach them.
    //
    // TEXT FIELDS rather than amount ones, and that is D15 rather than laziness. The renderer draws
    // `plainValue`; for an `AmountValue` that is "12400", and a price with a currency and a separator
    // is TEXT — which only the server is allowed to produce. So the server sends the formatted string
    // and the client renders it without deciding what money looks like anywhere.
    //
    // No rules on either: a value nobody can type into cannot be invalid. The affordability refusal
    // is the server's, stated beside the balance and enforced again at submit.
    fun schema(
        balance: Money,
        price: Money,
    ): FormSchema =
        FormSchema(
            formId = CustomPackageFields.FORM_ID,
            fields =
                listOf(
                    quantityField(CustomPackageFields.DATA_GB, CustomPackageTariff.DATA_GB_STEPS.first()),
                    quantityField(CustomPackageFields.MINUTES, CustomPackageTariff.MINUTES_STEPS.first()),
                    quantityField(CustomPackageFields.MESSAGES, CustomPackageTariff.MESSAGES_STEPS.first()),
                    computedField(CustomPackageFields.PRICE, price),
                    computedField(CustomPackageFields.BALANCE, balance),
                ),
        )

    // A field the server owns end to end. `triggersPatch` is false: it never changes because somebody
    // touched it, it changes because something else did.
    private fun computedField(
        fieldId: String,
        value: Money,
    ) = TextFieldDefinition(
        fieldId = fieldId,
        rules = emptyList(),
        // So the controller starts holding what the screen already draws. Without it the first patch
        // request would carry a form whose price the client believes to be absent — harmless, since
        // the server computes it anyway, and confusing to read in a log.
        initialValue = TextValue(MoneyFormat.format(value)),
    )

    private fun quantityField(
        fieldId: String,
        first: Long,
    ) = SelectionFieldDefinition(
        fieldId = fieldId,
        triggersPatch = true,
        initialValue = EntityValue(id = first.toString(), title = first.toString()),
    )

    // The tree. `select_input` for each quantity, and a BOUND `read_only_field` for each computed
    // value.
    //
    // THE PRICE UPDATES IN PLACE. `FormPatch` changes values in the `FormController`, and since
    // kompot 0.33.0 a `read_only_field` carrying a `fieldId` reads that controller — bound for values
    // and for visibility, editable by nobody. Before that every bound component was editable and the
    // one non-editable display was unbound, so a server-computed value was either something a
    // subscriber could type into or something correct once and stale after. youndie/kompot#89.
    //
    // What is still passed as `value` is the FIRST PAINT rather than a fallback: a bound field with
    // nothing in it yet draws the server's own string instead of an empty box, and a client built
    // before `fieldId` existed draws it always.
    fun screen(
        balance: Money,
        price: Money,
    ): KompotComponent =
        ColumnComponent(
            id = "custom-package",
            spacing = 16,
            children =
                listOf(
                    TextComponent(id = "custom-package-title", text = "Build your own package"),
                    quantityInput(CustomPackageFields.DATA_GB, "Data, GB", CustomPackageTariff.DATA_GB_STEPS),
                    quantityInput(CustomPackageFields.MINUTES, "Minutes", CustomPackageTariff.MINUTES_STEPS),
                    quantityInput(CustomPackageFields.MESSAGES, "Messages", CustomPackageTariff.MESSAGES_STEPS),
                    ReadOnlyFieldComponent(
                        id = "custom-package-price",
                        fieldId = CustomPackageFields.PRICE,
                        label = "Price",
                        value = MoneyFormat.format(price),
                    ),
                    ReadOnlyFieldComponent(
                        id = "custom-package-balance",
                        fieldId = CustomPackageFields.BALANCE,
                        label = "Your balance",
                        value = MoneyFormat.format(balance),
                        // The refusal beside the number it is about. The helper text is part of the
                        // TREE rather than of the patch, so it is what a freshly opened form says; a
                        // patch that finds the package unaffordable says it by focusing the field.
                        helperText = affordability(price, balance),
                    ),
                    // THE WAY TO SAY YES, and it was not here at all until `B-87`: the form could
                    // answer a price and its terminal state was a number.
                    //
                    // ALWAYS OFFERED, INCLUDING WHEN THE PACKAGE CANNOT BE AFFORDED, and that is the
                    // opposite of what the tariff cards do. A card is withheld because the server
                    // would refuse the press; here the refusal is the POINT — it lands on the order
                    // screen, which names the reason and offers `Top up`
                    // (`B-68`). A button withheld on an affordability the client computed would also
                    // be a rule the client owns, and the whole reason the price is a patch is that it
                    // is not.
                    ButtonComponent(
                        id = "custom-package-submit",
                        text = "Order this package",
                        action = SubmitFormAction(CustomPackageFields.FORM_ID),
                        modifiers = FILLS_THE_ROW,
                    ),
                ),
        )

    // THE PATCH: what changes when a quantity moves, and nothing else.
    //
    // This is the whole reason B-20 exists — form-core's split. The steps a subscriber may choose are
    // validated on the client against the schema; only the price, which the client is not allowed to
    // compute, comes from here. The tree is not sent again, so nothing is redrawn and no field loses
    // what it holds.
    //
    // `focusOn` names the BALANCE when the package cannot be afforded, which is B-20's second
    // acceptance criterion in the form it actually asks for: the field is highlighted rather than a
    // sentence appearing somewhere near it. It could not be done until the balance was a field.
    fun patch(
        balance: Money,
        price: Money,
    ): FormPatch =
        FormPatch(
            updates =
                mapOf(
                    CustomPackageFields.PRICE to TextValue(MoneyFormat.format(price)),
                    // Sent every time even though it rarely moves: a form open while a top-up lands
                    // would otherwise price against a balance that is no longer there, and the one
                    // party that knows is this one.
                    CustomPackageFields.BALANCE to TextValue(MoneyFormat.format(balance)),
                ),
            focusOn = if (affordability(price, balance) != null) CustomPackageFields.BALANCE else null,
        )

    private fun quantityInput(
        fieldId: String,
        label: String,
        steps: List<Long>,
    ) = SelectInputComponent(
        id = "custom-package-$fieldId",
        fieldId = fieldId,
        label = label,
        options = steps.map { SelectOption(id = it.toString(), label = it.toString()) },
    )

    // WHETHER THIS PACKAGE CAN BE AFFORDED, decided by the server on every render.
    //
    // It is a sentence on the screen rather than a `focusOn` in a patch, and that is the same
    // constraint again: `FormPatch.focusOn` names a fieldId, and the balance is not a field for the
    // reason above. So the refusal is stated where a subscriber reads it, and the submit route
    // refuses again — a rule the client evaluates is a rule the client can skip.
    fun affordability(
        price: Money,
        balance: Money,
    ): String? =
        if (price.minorUnits > balance.minorUnits) {
            "That package costs more than your balance."
        } else {
            null
        }

    fun response(
        balance: Money,
        price: Money,
    ): KompotFormResponse = KompotFormResponse(schema = schema(balance, price), screen = screen(balance, price))
}

// What the three quantities are, read off a patch request. A value class rather than three Longs at
// every call site: three numbers of the same type in a row is an argument order nobody notices
// getting wrong.
data class CustomPackageQuantities(
    val dataGb: Long,
    val minutes: Long,
    val messages: Long,
)
