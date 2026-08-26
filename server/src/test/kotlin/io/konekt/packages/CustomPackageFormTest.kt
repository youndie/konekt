package io.konekt.packages

import io.github.youndie.kompot.form.standard.EntityValue
import io.github.youndie.kompot.forms.ReadOnlyFieldComponent
import io.github.youndie.kompot.forms.SelectInputComponent
import io.github.youndie.kompot.standard.ColumnComponent
import io.konekt.domain.Currency
import io.konekt.domain.Money
import io.konekt.feature.packages.shared.api.CustomPackageFields
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CustomPackageFormTest {
    private val balance = Money.ofMajor(20, Currency.DEFAULT)

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
        val schema = CustomPackageForm.schema()

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
    fun `only the quantities are fields, because a computed one cannot be both declared and shown`() {
        // The conformance kit found this the moment it had a form to look at: `price` and `balance`
        // were declared and rendered by nothing, which SPEC §9.2 refuses. The only non-editable
        // display is not bound to the controller, so a computed value is declared OR shown
        // (youndie/kompot#89) — and a schema declaring a field nothing renders is a schema that lies
        // about its own form.
        assertEquals(
            listOf(CustomPackageFields.DATA_GB, CustomPackageFields.MINUTES, CustomPackageFields.MESSAGES),
            CustomPackageForm.schema().fields.map { it.fieldId },
        )
    }

    @Test
    fun `a package the balance cannot cover says so beside the balance`() {
        // B-20's second acceptance criterion, as near as the wire allows. It asks for the balance
        // FIELD to be highlighted; `FormPatch.focusOn` names a fieldId and the balance is not one, so
        // the refusal is stated where a subscriber reads it and the submit route refuses again.
        val tooMuch = CustomPackageTariff.priceOf(50, 1_200, 500)

        assertNotNull(CustomPackageForm.affordability(tooMuch, balance))
        assertNull(
            CustomPackageForm.affordability(CustomPackageTariff.priceOf(0, 0, 0), balance),
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
    }

    @Test
    fun `every quantity input offers exactly the steps the server prices`() {
        val screen = CustomPackageForm.screen(balance, Money.ofMajor(0, Currency.DEFAULT)) as ColumnComponent
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
