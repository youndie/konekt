package io.konekt.packages

import io.github.youndie.kompot.form.standard.EntityValue
import io.github.youndie.kompot.form.standard.TextValue
import io.github.youndie.kompot.forms.ReadOnlyFieldComponent
import io.github.youndie.kompot.forms.SelectInputComponent
import io.github.youndie.kompot.standard.ColumnComponent
import io.konekt.domain.Currency
import io.konekt.domain.Money
import io.konekt.feature.packages.shared.api.CustomPackageFields
import io.konekt.money.MoneyFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CustomPackageFormTest {
    private val balance = Money.ofMajor(20, Currency.DEFAULT)
    private val zero = Money.ofMajor(0, Currency.DEFAULT)

    @Test
    fun `the price is the sum of the three quantities, and nothing costs nothing`() {
        // The floor is a real state rather than an edge case: the form opens on it, so a tariff that
        // charged for an empty package would charge before the subscriber chose anything.
        assertEquals(0, CustomPackageTariff.priceOf(0, 0, 0).minorUnits)

        // Each quantity priced alone, then together — a total that is right can be reached by a
        // formula that weights the wrong one, and three separate assertions cannot say that.
        val alone =
            CustomPackageTariff.priceOf(10, 0, 0).minorUnits +
                CustomPackageTariff.priceOf(0, 300, 0).minorUnits +
                CustomPackageTariff.priceOf(0, 0, 200).minorUnits
        assertEquals(alone, CustomPackageTariff.priceOf(10, 300, 200).minorUnits)
    }

    @Test
    fun `a quantity outside the steps is not a quantity`() {
        assertTrue(CustomPackageTariff.isStep(10, CustomPackageTariff.DATA_GB_STEPS))
        // Between two steps, which is the value a client would send if it rounded rather than chose.
        assertTrue(!CustomPackageTariff.isStep(7, CustomPackageTariff.DATA_GB_STEPS))
    }

    @Test
    fun `every quantity field asks the server to reprice`() {
        val schema = CustomPackageForm.schema(balance, zero)

        val quantities = setOf(CustomPackageFields.DATA_GB, CustomPackageFields.MINUTES, CustomPackageFields.MESSAGES)
        val triggering =
            schema.fields
                .filter { it.triggersPatch }
                .map { it.fieldId }
                .toSet()

        // All three, because any of them moving changes the price. A quantity that does not trigger
        // is a price that stops updating when that one moves — and only that one, which is the kind
        // of gap nobody notices from a screenshot.
        assertEquals(quantities, triggering)
    }

    @Test
    fun `every declared field is rendered by a component that names it`() {
        // SPEC §9.2, held here rather than only by the conformance walk, because the walk needs a
        // running server and this is the rule the form got wrong the first time it existed: `price`
        // and `balance` were declared and rendered by nothing.
        //
        //     [form-fields] field "balance" is declared but never rendered
        //
        // It is the mirror of that finding. The two computed fields are declared AND carried by a
        // `read_only_field` — which since kompot 0.33.0 may name a fieldId and be bound without being
        // editable (youndie/kompot#89). That binding is what lets a patch reach them.
        val schema = CustomPackageForm.schema(balance, zero)
        val screen = CustomPackageForm.screen(balance, zero) as ColumnComponent

        assertEquals(
            listOf(
                CustomPackageFields.DATA_GB,
                CustomPackageFields.MINUTES,
                CustomPackageFields.MESSAGES,
                CustomPackageFields.PRICE,
                CustomPackageFields.BALANCE,
            ),
            schema.fields.map { it.fieldId },
        )

        val rendered =
            screen.children
                .mapNotNull {
                    when (it) {
                        is SelectInputComponent -> it.fieldId
                        is ReadOnlyFieldComponent -> it.fieldId
                        else -> null
                    }
                }.toSet()

        assertEquals(emptySet(), schema.fields.map { it.fieldId }.toSet() - rendered, "declared and never rendered")
        assertEquals(emptySet(), rendered - schema.fields.map { it.fieldId }.toSet(), "rendered and never declared")
    }

    @Test
    fun `a patch carries the two computed values and no tree`() {
        // THE ACCEPTANCE CRITERION AS A MECHANISM. What comes back when a quantity moves is two
        // values, not a screen — which is what makes the update in place rather than a redraw.
        val price = CustomPackageTariff.priceOf(10, 300, 200)
        val patch = CustomPackageForm.patch(balance, price)

        assertEquals(
            mapOf(
                CustomPackageFields.PRICE to MoneyFormat.format(price),
                CustomPackageFields.BALANCE to MoneyFormat.format(balance),
            ),
            patch.updates.mapValues { it.value.plainValue },
        )
        // Formatted, and by the server. The renderer draws `plainValue` verbatim, so an AmountValue
        // here would put "3400" on the screen and a client deciding what money looks like is exactly
        // what D15 forbids.
        assertTrue(patch.updates.values.all { it is TextValue }, "a computed value must arrive as text")
        assertEquals(emptyList(), patch.clearFields)
    }

    @Test
    fun `a patch names only fields the schema declares`() {
        // THE CHECK NOBODY ELSE MAKES. The conformance kit holds this rule for a form — every fieldId
        // a component names must be declared — but it has no notion of a patch endpoint: it reads four
        // kinds and a `FormPatch` is none of them, so this route is walked by nothing.
        //
        // A patch naming a field the schema does not have is silent in every other way: the controller
        // keys by string, so the value goes to a field nobody renders and the screen simply stops
        // updating. This is the only thing standing between that and a release.
        val declared =
            CustomPackageForm
                .schema(balance, zero)
                .fields
                .map { it.fieldId }
                .toSet()

        // Both the affordable and the refused patch, because `focusOn` is null in one of them and a
        // single case would leave the branch that actually names a field unchecked.
        listOf(zero, CustomPackageTariff.priceOf(50, 1_200, 500)).forEach { price ->
            val patch = CustomPackageForm.patch(balance, price)
            assertEquals(emptySet(), patch.updates.keys - declared, "a patch updates an undeclared field")
            assertEquals(emptySet(), patch.clearFields.toSet() - declared, "a patch clears an undeclared field")
            patch.focusOn?.let { assertTrue(it in declared, "a patch focuses undeclared field \"$it\"") }
        }
    }

    @Test
    fun `a package the balance cannot cover says so beside the balance`() {
        // B-20's second acceptance criterion, in the form it actually asks for. It wants the balance
        // FIELD highlighted; `FormPatch.focusOn` names a fieldId, and the balance is one now — so the
        // patch points at it, and the freshly-opened form says it in words beside the number. The
        // submit route refuses a third time, because a rule the client evaluates is one it can skip.
        val tooMuch = CustomPackageTariff.priceOf(50, 1_200, 500)

        assertNotNull(CustomPackageForm.affordability(tooMuch, balance))
        assertNull(
            CustomPackageForm.affordability(zero, balance),
            "an affordable package was refused",
        )

        val screen = CustomPackageForm.screen(balance, tooMuch) as ColumnComponent
        val balanceField =
            assertNotNull(
                screen.children
                    .filterIsInstance<ReadOnlyFieldComponent>()
                    .firstOrNull { it.id == "custom-package-balance" },
            )
        assertNotNull(balanceField.helperText, "the balance was not the thing that said why")

        // And a patch computed for the same package points at that field rather than at the price:
        // the price is correct, it is the money behind it that is not there.
        assertEquals(CustomPackageFields.BALANCE, CustomPackageForm.patch(balance, tooMuch).focusOn)
        assertNull(
            CustomPackageForm.patch(balance, zero).focusOn,
            "an affordable package highlighted a field anyway",
        )
    }

    @Test
    fun `every quantity input offers exactly the steps the server prices`() {
        val screen = CustomPackageForm.screen(balance, zero) as ColumnComponent
        val inputs = screen.children.filterIsInstance<SelectInputComponent>().associateBy { it.fieldId }

        // The list a subscriber picks from and the list the server accepts are the same list. Two
        // copies would let a client offer a size the server refuses, which reads as a broken form.
        assertEquals(
            CustomPackageTariff.DATA_GB_STEPS.map { it.toString() },
            assertNotNull(inputs[CustomPackageFields.DATA_GB]).options.map { it.id },
        )
        assertEquals(3, inputs.size, "expected one input per quantity, got ${inputs.keys}")
    }
}
