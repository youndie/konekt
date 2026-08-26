package io.konekt.feature.packages.shared.api

import io.ktor.resources.Resource

// THE CUSTOM PACKAGE, as two addresses: the form, and the recalculation.
//
// A resource tree of its own rather than a verb on `Purchases`, because what this builds is not yet a
// purchase — it is a price being negotiated. The order it eventually creates goes through the same
// saga as anything else.
// TWO ADDRESSES: the form, and the patch that keeps it current without redrawing it.
//
// It was one for a while. `FormPatch` is how a form updates a value without redrawing — the whole
// reason a subscriber does not lose what they have chosen on every change — and it could not be used:
// a patch updates fields in the `FormController`, only bound components read that, and every bound
// component was editable, so the price was either something a subscriber could type into or something
// a patch could not reach. Filed as youndie/kompot#89 and fixed in kompot 0.33.0, where
// `read_only_field` takes an optional `fieldId` and is bound without being editable.
//
// So the refetch is gone. The GET is the first paint; every change after it is a patch.
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

    // The computed ones. Schema fields AND rendered, which is what lets a patch reach them; the
    // constant is named once because four parties spell it — the schema, the component tree, the
    // patch, and the client that reads the answer.
    const val PRICE = "price"
    const val BALANCE = "balance"

    // The form id, which the patch request carries so the server knows which form is asking.
    const val FORM_ID = "custom-package"
}

// WHERE A CHANGED QUANTITY GOES. POST rather than GET because the client sends the form's whole
// current state in the body — `FormPatchRequest` from kompot-forms, which is the shape
// `FormController.patchFetcher` produces — and because a body is not a query string a proxy logs.
//
// It answers a `FormPatch` and nothing else: no schema, no tree. That is the point.
@Resource("/api/v1/forms/custom-package/patch")
class CustomPackagePatch
