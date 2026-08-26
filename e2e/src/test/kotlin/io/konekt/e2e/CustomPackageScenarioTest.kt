package io.konekt.e2e

import io.github.youndie.kompot.form.FieldValue
import io.github.youndie.kompot.form.FormPatch
import io.github.youndie.kompot.form.standard.EntityValue
import io.github.youndie.kompot.forms.FormPatchRequest
import io.github.youndie.kompot.forms.KompotFormResponse
import io.github.youndie.kompot.forms.ReadOnlyFieldComponent
import io.github.youndie.kompot.forms.SelectInputComponent
import io.github.youndie.kompot.standard.ColumnComponent
import io.konekt.feature.packages.shared.api.CustomPackageFields
import io.konekt.feature.packages.shared.api.CustomPackageForm
import io.konekt.feature.packages.shared.api.CustomPackagePatch
import io.ktor.client.HttpClient
import io.ktor.client.plugins.resources.get
import io.ktor.client.plugins.resources.post
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// THE FORM HALF OF THE TOOLKIT, over five processes — the first thing in this build to use it.
//
// What matters here and cannot be checked below this level: the schema and the tree the server sends
// are decodable by a CLIENT's Json. That is not free — a `FormSchema` carries polymorphic field
// definitions and a tree carries their components, registered in two different modules, and a client
// with only one of them decodes the screen and fails on the schema. This suite found exactly that,
// at `$.schema.fields[0]`, the first time it asked for a form.
class CustomPackageScenarioTest {
    private suspend fun formFor(
        client: io.ktor.client.HttpClient,
        session: Stand.Session,
        dataGb: Long? = null,
    ): KompotFormResponse =
        client
            .get(CustomPackageForm(dataGb = dataGb)) { bearerAuth(session.accessToken) }
            .let { Stand.json.decodeFromString(KompotFormResponse.serializer(), it.bodyAsText()) }

    @Test
    fun `the form arrives whole, and every field it declares is rendered by something`() =
        runBlocking {
            Stand.client().use { client ->
                val session = Stand.signIn(client)
                val response = formFor(client, session)

                assertEquals(CustomPackageFields.FORM_ID, response.schema.formId)

                // SPEC §9.2's connectivity rule, asserted here as well as by the conformance kit: a
                // declared fieldId with nothing rendering it is a form that cannot be filled in, and
                // it is the defect this screen shipped with for an hour.
                // BOTH KINDS OF BOUND COMPONENT, and this test only counted one of them until the
                // computed values became fields. A `read_only_field` may name a fieldId since kompot
                // 0.33.0 (youndie/kompot#89) and is then bound without being editable — which is what
                // lets a patch reach a price. Counting only the inputs made this assertion say the
                // price was rendered by nothing, which is the reverse of what the tree does.
                val rendered =
                    (response.screen as ColumnComponent)
                        .children
                        .mapNotNull {
                            when (it) {
                                is SelectInputComponent -> it.fieldId
                                is ReadOnlyFieldComponent -> it.fieldId
                                else -> null
                            }
                        }.toSet()

                assertEquals(
                    emptySet(),
                    response.schema.fields
                        .map { it.fieldId }
                        .toSet() - rendered,
                    "the schema declares fields the tree renders with nothing",
                )
            }
        }

    @Test
    fun `choosing a size reprices, and the server is the one that priced it`() =
        runBlocking {
            Stand.client().use { client ->
                val session = Stand.signIn(client)

                val empty = priceOn(formFor(client, session))
                val ten = priceOn(formFor(client, session, dataGb = 10))

                // The client owns no tariff and no formatter for money, so both strings can only have
                // been composed by the server — and they differ, which a cached response would not.
                assertEquals("$0", empty)
                assertEquals("$15", ten, "ten gigabytes did not cost what the tariff says")
            }
        }

    @Test
    fun `a size the package does not come in is refused rather than rounded`() =
        runBlocking {
            Stand.client().use { client ->
                val session = Stand.signIn(client)

                // Between two steps: what a client would send if it rounded rather than chose from
                // the list the server prices.
                val refused = client.get(CustomPackageForm(dataGb = 7)) { bearerAuth(session.accessToken) }

                assertEquals(HttpStatusCode.UnprocessableEntity, refused.status)
            }
        }

    @Test
    fun `a new subscriber is told the package costs more than the balance`(): Unit =
        runBlocking {
            Stand.client().use { client ->
                val session = Stand.signIn(client)

                val affordable = balanceFieldOn(formFor(client, session))
                assertNull(affordable.helperText, "an empty package was refused")

                val tooMuch = balanceFieldOn(formFor(client, session, dataGb = 50))
                assertNotNull(tooMuch.helperText, "a package beyond the balance said nothing")
            }
        }

    @Test
    fun `a patch reprices without sending the form again`(): Unit =
        runBlocking {
            Stand.client().use { client ->
                val session = Stand.signIn(client)

                // What a `FormController` posts when a field with triggersPatch moves: the field that
                // changed, and the whole form as the client currently holds it.
                val patch =
                    patchFor(
                        client,
                        session,
                        fieldId = CustomPackageFields.DATA_GB,
                        values =
                            mapOf(
                                CustomPackageFields.DATA_GB to EntityValue(id = "10", title = "10"),
                                CustomPackageFields.MINUTES to EntityValue(id = "0", title = "0"),
                                CustomPackageFields.MESSAGES to EntityValue(id = "0", title = "0"),
                            ),
                    )

                // THE WHOLE POINT: two values and no tree. A response carrying a screen would be the
                // refetch this endpoint exists to replace, and the client would redraw.
                assertEquals("$15", patch.updates[CustomPackageFields.PRICE]?.plainValue)
                assertEquals("$0", patch.updates[CustomPackageFields.BALANCE]?.plainValue)
                assertEquals(setOf(CustomPackageFields.PRICE, CustomPackageFields.BALANCE), patch.updates.keys)

                // A new subscriber has nothing, so $15 is beyond them — and the field the patch points
                // at is the balance rather than the price. The price is correct; it is the money
                // behind it that is not there.
                assertEquals(CustomPackageFields.BALANCE, patch.focusOn)
            }
        }

    @Test
    fun `a patch for a size the package does not come in is refused too`() =
        runBlocking {
            Stand.client().use { client ->
                val session = Stand.signIn(client)

                // The same refusal the GET makes. A rule enforced on one of two doors is not enforced:
                // the patch endpoint takes quantities from a body rather than a query, and nothing
                // about that makes seven gigabytes a size this package comes in.
                val refused =
                    client.post(CustomPackagePatch()) {
                        bearerAuth(session.accessToken)
                        contentType(ContentType.Application.Json)
                        setBody(
                            Stand.json.encodeToString(
                                FormPatchRequest.serializer(),
                                FormPatchRequest(
                                    formId = CustomPackageFields.FORM_ID,
                                    fieldId = CustomPackageFields.DATA_GB,
                                    values = mapOf(CustomPackageFields.DATA_GB to EntityValue(id = "7", title = "7")),
                                ),
                            ),
                        )
                    }

                assertEquals(HttpStatusCode.UnprocessableEntity, refused.status)
            }
        }

    private suspend fun patchFor(
        client: HttpClient,
        session: Stand.Session,
        fieldId: String,
        values: Map<String, FieldValue>,
    ): FormPatch =
        Stand.json.decodeFromString(
            FormPatch.serializer(),
            client
                .post(CustomPackagePatch()) {
                    bearerAuth(session.accessToken)
                    contentType(ContentType.Application.Json)
                    setBody(
                        Stand.json.encodeToString(
                            FormPatchRequest.serializer(),
                            FormPatchRequest(formId = CustomPackageFields.FORM_ID, fieldId = fieldId, values = values),
                        ),
                    )
                }.bodyAsText(),
        )

    private fun priceOn(response: KompotFormResponse): String =
        (response.screen as ColumnComponent)
            .children
            .filterIsInstance<ReadOnlyFieldComponent>()
            .first { it.id == "custom-package-price" }
            .value

    private fun balanceFieldOn(response: KompotFormResponse): ReadOnlyFieldComponent =
        (response.screen as ColumnComponent)
            .children
            .filterIsInstance<ReadOnlyFieldComponent>()
            .first { it.id == "custom-package-balance" }
}
