package io.konekt.feature.packages.shared.api

import io.ktor.resources.Resource

// THE CUSTOM PACKAGE, as two addresses: the form, and the recalculation.
//
// A resource tree of its own rather than a verb on `Purchases`, because what this builds is not yet a
// purchase — it is a price being negotiated. The order it eventually creates goes through the same
// saga as anything else.
// ONE ADDRESS, WITH THE QUANTITIES IN THE QUERY, and it started as two.
//
// The second was a patch endpoint: `FormPatch` is how a form updates a value without redrawing, which
// is the whole reason a subscriber does not lose focus on every change. It cannot be used here.
// A patch updates fields in the `FormController`, only bound components read that, and every bound
// component is editable — so the price is either something a subscriber can type into or something a
// patch cannot reach. youndie/kompot#89.
//
// So the form is refetched with what has been chosen so far. That costs the focus AC 1 asks to keep,
// and it is the honest shape until the toolkit has a field that is bound and not editable.
@Resource("/api/v1/forms/custom-package")
class CustomPackageForm(
    // Absent means nothing chosen yet, which is what the form opens on. Not defaulted to a required
    // value: a form that refused to open until three quantities were supplied would be a form nobody
    // can open.
    val dataGb: Long? = null,
    val minutes: Long? = null,
    val messages: Long? = null,
)

// The field ids, in one place because three parties spell them: the schema, the component tree, and
// the patch that updates them. A typo in any one is a field that silently never updates — the
// controller keys by string, so nothing fails, the value simply goes to a field nobody renders.
object CustomPackageFields {
    const val DATA_GB = "data_gb"
    const val MINUTES = "minutes"
    const val MESSAGES = "messages"

    // The computed ones. NOT schema fields — see the note on the resource above — but the ids the
    // read-only components carry, so both sides still name one constant.
    const val PRICE = "price"
    const val BALANCE = "balance"

    // The form id, which the patch request carries so the server knows which form is asking.
    const val FORM_ID = "custom-package"
}
