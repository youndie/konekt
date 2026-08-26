package io.konekt.packages

import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.form.FormSchema
import io.github.youndie.kompot.form.standard.EntityValue
import io.github.youndie.kompot.form.standard.SelectionFieldDefinition
import io.github.youndie.kompot.forms.KompotFormResponse
import io.github.youndie.kompot.forms.ReadOnlyFieldComponent
import io.github.youndie.kompot.forms.SelectInputComponent
import io.github.youndie.kompot.forms.SelectOption
import io.github.youndie.kompot.standard.ColumnComponent
import io.github.youndie.kompot.standard.TextComponent
import io.konekt.domain.Money
import io.konekt.feature.packages.shared.api.CustomPackageFields
import io.konekt.money.MoneyFormat

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
    // ONLY THE THREE QUANTITIES, and the two computed values are deliberately NOT fields.
    //
    // They were, for about an hour, with a `MaxAmountRule` on the price reading the balance out of a
    // neighbouring field's metadata — which is the pattern kompot's readme describes. The conformance
    // kit refused it the moment it had a form to look at:
    //
    //     [form-fields] field "balance" is declared but never rendered
    //     [form-fields] field "price" is declared but never rendered
    //
    // and it was right. SPEC §9.2 asks that every declared fieldId have a component rendering it, and
    // the only non-editable display — `read_only_field` — is not bound to the controller. So a
    // computed field can be declared or displayed, not both (youndie/kompot#89). Declaring one and
    // rendering it as text is a schema that lies about its own form.
    fun schema(): FormSchema =
        FormSchema(
            formId = CustomPackageFields.FORM_ID,
            fields =
                listOf(
                    quantityField(CustomPackageFields.DATA_GB, CustomPackageTariff.DATA_GB_STEPS.first()),
                    quantityField(CustomPackageFields.MINUTES, CustomPackageTariff.MINUTES_STEPS.first()),
                    quantityField(CustomPackageFields.MESSAGES, CustomPackageTariff.MESSAGES_STEPS.first()),
                ),
        )

    private fun quantityField(
        fieldId: String,
        first: Long,
    ) = SelectionFieldDefinition(
        fieldId = fieldId,
        triggersPatch = true,
        initialValue = EntityValue(id = first.toString(), title = first.toString()),
    )

    // The tree. `select_input` for each quantity and a `read_only_field` for the price.
    //
    // THE PRICE CANNOT UPDATE IN PLACE, and that is upstream rather than a shortcut here.
    // `FormPatch` changes values in the `FormController`, and only BOUND components read it — every
    // one of which is editable. `read_only_field` is the single non-editable display and it is
    // explicitly not bound: its renderer draws `component.value` and never touches the controller it
    // is handed. So a server-computed value is either editable or stale. youndie/kompot#89.
    //
    // konekt draws it correct-and-stale rather than live-and-editable: a price a subscriber can type
    // into is worse than one that needs a redraw.
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
                        label = "Price",
                        value = MoneyFormat.format(price),
                    ),
                    ReadOnlyFieldComponent(
                        id = "custom-package-balance",
                        label = "Your balance",
                        value = MoneyFormat.format(balance),
                        // The refusal, beside the number it is about. B-20 asks for the balance to be
                        // the thing highlighted; without a bound field to point at, saying it here is
                        // the nearest honest thing.
                        helperText = affordability(price, balance),
                    ),
                ),
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
    ): KompotFormResponse = KompotFormResponse(schema = schema(), screen = screen(balance, price))
}

// What the three quantities are, read off a patch request. A value class rather than three Longs at
// every call site: three numbers of the same type in a row is an argument order nobody notices
// getting wrong.
data class CustomPackageQuantities(
    val dataGb: Long,
    val minutes: Long,
    val messages: Long,
)
