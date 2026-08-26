package io.konekt.openapi

import io.konekt.feature.auth.shared.api.AuthOtp
import io.konekt.feature.auth.shared.api.AuthSession
import io.konekt.feature.auth.shared.api.DevOtp
import io.konekt.feature.esim.shared.api.EsimWizardResource
import io.konekt.feature.packages.shared.api.CustomPackageForm
import io.konekt.feature.packages.shared.api.CustomPackagePatch
import io.konekt.feature.purchase.shared.api.HistoryScreenResource
import io.konekt.feature.purchase.shared.api.OrderScreen
import io.konekt.feature.purchase.shared.api.Purchases
import io.konekt.feature.purchase.shared.api.TopUps
import io.konekt.feature.realtime.shared.api.RealtimeStream
import io.konekt.feature.tariff.shared.api.TariffChanges
import io.konekt.feature.theme.shared.api.BrandTheme
import io.konekt.feature.usage.shared.api.HomeScreenResource
import io.konekt.roaming.dev.ArriveResource
import io.konekt.screens.dev.FailingResource
import io.konekt.screens.dev.ForwardCompatScreenResource
import io.ktor.resources.serialization.ResourcesFormat
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

// What an endpoint answers, which the routing tree cannot know.
//
// THE SPLIT IS THE DESIGN. Everything Ktor already knows — the path, the method, the query
// parameters, the auth tier — is read out of the tree in `RouteInventory` and never written here.
// What is left is what only the handler knows: which status it answers with, what the body is, and
// which kompot vocabulary the body belongs to. That half is written down, so it can drift; the
// answer to drift is `OpenApiDocument`, which refuses to build a document unless the two halves name
// exactly the same set of endpoints.
data class EndpointFacts(
    val summary: String,
    // Absent means "this route serves none of the kompot vocabulary". See EndpointKind for why that
    // is a better answer than the nearest-looking word.
    val kind: String? = null,
    val successStatus: Int = 200,
    // null means the success response carries no body at all — 204, and nothing else here.
    val successContentType: String? = "application/json",
    // A `$ref` into the committed wire specification, by file name and pointer, exactly as the
    // conformance kit resolves it. Only a body the spec actually describes may name one: the kit
    // validates the response against whatever this points at, so a ref to a schema nobody publishes
    // is a finding about the server rather than about the document.
    val successBodyRef: String? = null,
    // The Kotlin type of a body that the wire specification does NOT describe. It becomes an
    // untyped object in the document with this name in its description — an admitted gap rather than
    // a hand-written copy of a data class that would rot. See docs/api/api-openapi.md.
    val successBodyType: String? = null,
    // The refusals this endpoint can produce, each one read in the route or in the use case behind
    // it. 401 for a secured route and 500 for everything are added by the generator, because those
    // two are properties of the composition rather than of any handler.
    val refusals: Set<Int> = emptySet(),
)

// The refs, spelled by file name and JSON pointer the way `kompot-tck` resolves them. The screen ref
// is the toolkit's own fallback constant for a route of kind `screen`, so the document agrees with
// what the kit would have assumed had it been left out.
object WireSchema {
    const val PROFILE_COMPONENT = "kompot.profile.schema.json#/\$defs/KompotComponent"
    const val PAGE_RESPONSE = "kompot-standard.schema.json#/\$defs/KompotPageResponse"
    const val UPDATE_SESSION = "kompot-auth.schema.json#/\$defs/KompotActionUpdateSession"
    const val UPDATE_FRAME = "kompot-realtime.schema.json#/\$defs/UpdateComponentMessage"
}

// NO ADDRESS IS WRITTEN IN THIS FILE, and that is the repository's rule rather than tidiness: a path
// spelled outside a `*-shared-api` module is a second spelling of the contract, and a renamed segment
// then becomes a 404 in somebody's hands instead of a compile error.
//
// So the key of an entry below is ASKED of the `@Resource` class, through the very encoder
// `ktor-server-resources` uses to build the routing tree — the two cannot produce different strings.
// A renamed segment moves both sides at once and the document follows without anybody noticing it
// had to.
object ResourceAddresses {
    private val format = ResourcesFormat()

    // The leading slash is put back on. `encodeToPathPattern` strips it as its last statement —
    // `createRouteFromPath` splits on segments and does not care — while an OpenAPI address is
    // rooted, and so is what Ktor's own `OpenApiRoutePathFormat` renders out of the tree. Without
    // this the two halves differ by one character on every entry, which is exactly how the
    // divergence check first reported itself.
    fun <T : Any> of(serializer: KSerializer<T>): String =
        format.encodeToPathPattern(serializer).let { if (it.startsWith("/")) it else "/$it" }
}

// "POST /api/v1/auth/otp/verify", assembled rather than typed. Public because `inline reified` is
// the only way to reach a serializer from a type parameter, and internal would not be enough.
inline fun <reified T : Any> endpointKey(method: String): String = "$method ${resourceAddress<T>()}"

// The declared address on its own, placeholders and all. `TckConfig` keys its path parameters by it,
// and the conformance walk plan has to name the same string the HTTP description prints — which is
// exactly the string a reader would otherwise copy out of the document by hand.
inline fun <reified T : Any> resourceAddress(): String = ResourceAddresses.of(serializer<T>())

// The declared half, by "METHOD path".
//
// The keys are not free text: `OpenApiDocument` holds them against the routing tree and fails on the
// first one that names a route the server does not serve, or leaves out one it does.
val konektEndpointFacts: Map<String, EndpointFacts> =
    mapOf(
        // The one route in this build with no `@Resource` behind it, because it is not part of the
        // product's API surface: it exists so a supervisor can ask the process a question rather
        // than ask the kernel whether a port accepts.
        "GET /health" to
            EndpointFacts(
                summary = "Answer while the process is alive",
                successContentType = "text/plain",
                successBodyType = "the two-letter string ok",
            ),
        // The brand kit. Keyed by the constant rather than by a `@Resource`, like `/health` above and
        // for the same reason: the address exists as a string in `:feature:theme-shared-api` because
        // the client fetches it before it has any typed routing, and one spelling is the whole point.
        "GET ${BrandTheme.PATH}" to
            EndpointFacts(
                summary = "The colour kit this deployment is branded with",
                // NOT `screen`. It answers a `KompotTheme` — the values a client resolves design-system
                // tokens into — which is neither a component tree nor an action, and borrowing the
                // nearest-looking kind is how a conformant server becomes a page of findings.
                successBodyType = "io.github.youndie.kompot.theme.KompotTheme",
            ),
        endpointKey<AuthOtp.Request>("POST") to
            EndpointFacts(
                summary = "Send a one-time code to a number",
                // Deliberately no kind. It answers a DTO of ours rather than a KompotAction, so it
                // is not a `submit`, and calling it one would tell the kit to expect an action.
                successBodyType = "io.konekt.feature.auth.shared.api.RequestOtpResponse",
                // 422 from Msisdn.parse, 429 from the two rate limits in RequestOtpUseCase.
                refusals = setOf(422, 429),
            ),
        endpointKey<AuthOtp.Verify>("POST") to
            EndpointFacts(
                summary = "Exchange a one-time code for a session",
                // A real submit: it answers `update_session`, through respondKompotAction. This is
                // the endpoint the conformance kit is pointed at to get a token.
                kind = EndpointKind.SUBMIT,
                successBodyRef = WireSchema.UPDATE_SESSION,
                // 422 for a wrong or expired code, 429 for the lockout — both in VerifyOtpUseCase.
                refusals = setOf(422, 429),
            ),
        endpointKey<AuthSession.Refresh>("POST") to
            EndpointFacts(
                summary = "Exchange a refresh token for a new pair",
                kind = EndpointKind.SUBMIT,
                successBodyRef = WireSchema.UPDATE_SESSION,
                // Every refusal in RefreshSessionUseCase is Unauthorized, including a token
                // presented twice — which ends the family rather than answering something softer.
                refusals = setOf(401),
            ),
        endpointKey<AuthSession.Logout>("POST") to
            EndpointFacts(
                summary = "End the calling session's family",
                successStatus = 204,
                successContentType = null,
            ),
        endpointKey<Purchases>("POST") to
            EndpointFacts(
                summary = "Start a purchase saga for one plan",
                // 202 and not 201: the usual answer is a saga waiting for a confirmation, and
                // "created" would be a claim the client has to unlearn.
                successStatus = 202,
                successBodyType = "io.konekt.feature.purchase.shared.api.PurchaseOrderResponse",
                // StartPurchaseUseCase answers NotFound for an unknown plan and for a subscriber
                // with no account.
                refusals = setOf(404),
            ),
        endpointKey<Purchases.ById>("GET") to
            EndpointFacts(
                summary = "The order as data",
                successBodyType = "io.konekt.feature.purchase.shared.api.PurchaseOrderResponse",
                // 404 and not 403 for somebody else's order — a 403 is an enumeration oracle.
                refusals = setOf(404),
            ),
        endpointKey<Purchases.ById.Confirm>("POST") to
            EndpointFacts(
                summary = "Answer the confirmation the saga is waiting for",
                successBodyType = "io.konekt.feature.purchase.shared.api.PurchaseOrderResponse",
                // 409 for an order that has already finished or is not waiting for anything.
                refusals = setOf(404, 409),
            ),
        endpointKey<TopUps>("POST") to
            EndpointFacts(
                summary = "Put money on the account",
                // NOT `submit`. It answers a TopUpResponse, which is a DTO rather than a KompotAction,
                // and `submit` asserts the latter — the one thing that kind is read for is
                // `performTargetsAreSubmitEndpoints`. A kind borrowed because it is the nearest-looking
                // word turns a conformant server into a page of findings about a contract it never
                // made.
                successBodyType = "io.konekt.feature.purchase.shared.api.TopUpResponse",
                // 422 for an amount outside the limits, 404 for a subscriber with no account. A
                // refusal by the payment provider is NOT here: it is a 201 whose body carries the
                // reason, because the top-up exists and its answer is "no".
                refusals = setOf(404, 422),
            ),
        endpointKey<TopUps.ById>("GET") to
            EndpointFacts(
                summary = "One top-up, as its owner sees it",
                successBodyType = "io.konekt.feature.purchase.shared.api.TopUpResponse",
                // 404 and not 403 for somebody else's top-up — a 403 is an enumeration oracle.
                refusals = setOf(404),
            ),
        endpointKey<TariffChanges>("POST") to
            EndpointFacts(
                summary = "Ask to change tariff, from the next billing boundary",
                successBodyType = "io.konekt.feature.tariff.shared.api.TariffChangeResponse",
                // 404 for a tariff that is not in the catalogue; 409 when a change is already waiting
                // for a confirmation — one at a time, because two would race for the same boundary.
                refusals = setOf(404, 409),
            ),
        endpointKey<TariffChanges.ById.Confirm>("POST") to
            EndpointFacts(
                summary = "Answer the confirmation the tariff change is waiting for",
                successBodyType = "io.konekt.feature.tariff.shared.api.TariffChangeResponse",
                refusals = setOf(404, 409),
            ),
        endpointKey<CustomPackageForm>("GET") to
            EndpointFacts(
                summary = "The custom package builder: three quantities and a price",
                // `form`, and this is the first endpoint in this build that earns the word. It answers
                // a `KompotFormResponse` — a `FormSchema` plus a component tree — which is exactly
                // what the conformance kit's `form-fields` check looks for, and what it has been
                // finding nothing of.
                kind = EndpointKind.FORM,
                successBodyType = "io.github.youndie.kompot.forms.KompotFormResponse",
                // 422 for a quantity that is not one of the sizes this package comes in — refused
                // rather than rounded, because rounding charges for a package nobody chose.
                refusals = setOf(404, 422),
            ),
        endpointKey<CustomPackagePatch>("POST") to
            EndpointFacts(
                summary = "Reprice the custom package without redrawing it",
                // NO KIND, and that is the honest answer rather than the nearest-looking word. The
                // kit reads four — form, page, submit, graph — and a `FormPatch` is none of them: it
                // carries neither a schema, nor a tree, nor an action. Calling it `form` would put it
                // in front of the `form-fields` check, which would find no schema and quietly pass.
                successBodyType = "io.github.youndie.kompot.form.FormPatch",
                // 422 for a quantity outside the steps, the same refusal the GET makes and for the
                // same reason: the client picks from the list the server prices.
                refusals = setOf(404, 422),
            ),
        endpointKey<HomeScreenResource>("GET") to
            EndpointFacts(
                summary = "The home screen: balance and counters",
                kind = EndpointKind.SCREEN,
                successBodyRef = WireSchema.PROFILE_COMPONENT,
            ),
        endpointKey<HistoryScreenResource>("GET") to
            EndpointFacts(
                summary = "The operation history, first page included",
                kind = EndpointKind.SCREEN,
                successBodyRef = WireSchema.PROFILE_COMPONENT,
            ),
        endpointKey<HistoryScreenResource.Page>("GET") to
            EndpointFacts(
                summary = "The next page of history rows",
                kind = EndpointKind.PAGE,
                successBodyRef = WireSchema.PAGE_RESPONSE,
            ),
        endpointKey<OrderScreen>("GET") to
            EndpointFacts(
                summary = "The result screen of one order, rollback included",
                kind = EndpointKind.SCREEN,
                successBodyRef = WireSchema.PROFILE_COMPONENT,
                refusals = setOf(404),
            ),
        endpointKey<EsimWizardResource>("POST") to
            EndpointFacts(
                summary = "Begin an eSIM install run and draw its first step",
                // `screen`, because that is what the response IS. Not `submit`, which asserts a
                // KompotAction, and not `wizard_resume`, which asserts kompot-wizard's
                // WizardResumeRequest — this flow has neither (research-architecture §1.12).
                kind = EndpointKind.SCREEN,
                successBodyRef = WireSchema.PROFILE_COMPONENT,
            ),
        endpointKey<EsimWizardResource.Step>("POST") to
            EndpointFacts(
                summary = "Move a run along by posting back the action on its button",
                kind = EndpointKind.SCREEN,
                successBodyRef = WireSchema.PROFILE_COMPONENT,
                // 422 when the posted body is not this wizard's action, 404 for a run that is not
                // the caller's.
                refusals = setOf(404, 422),
            ),
        // The one address that cannot be a `@Resource`: both halves of SSE take a plain string and
        // `ktor-client-resources` has no SSE builder. So it is named through the same constant the
        // route is mounted from, which is the only way the single-spelling rule can be kept here.
        "GET ${RealtimeStream.PATH}" to
            EndpointFacts(
                summary = "The subscriber's update stream",
                kind = EndpointKind.UPDATES_STREAM,
                successContentType = "text/event-stream",
                // The frames, named rather than the stream. The kit reads a RECORDING of this
                // endpoint and holds each frame against this schema; it never opens the connection.
                successBodyType =
                    "a sequence of UpdateComponentMessage frames, one per data event; each is held " +
                        "against ${WireSchema.UPDATE_FRAME}",
            ),
    )

// The development route, in a map of its own for the same reason it is a route group of its own: it
// exists only when DEV_REVEAL_OTP=true, and the committed document describes a production
// deployment. Kept described rather than left out, because `OpenApiDocumentTest` builds the
// development document too — a description that is never built is a description that rots.
val devOtpEndpointFacts: Map<String, EndpointFacts> =
    mapOf(
        endpointKey<DevOtp>("GET") to
            EndpointFacts(
                summary = "Read back the code the SMSC would have carried (development only)",
                successBodyType = "io.konekt.feature.auth.shared.api.DevOtpResponse",
                // 404 is answered by the route itself when there is no outstanding code; 422 comes
                // from Msisdn.parse on the query parameter.
                refusals = setOf(404, 422),
            ),
    )

// The development SCREEN, in a third map for the same reason again: it exists only under
// `DEV_SCREENS`, which is a different switch from `DEV_REVEAL_OTP` because they are different
// decisions — a test environment may reasonably want the OTP readback without a demonstration screen
// in its route table.
val devScreensEndpointFacts: Map<String, EndpointFacts> =
    mapOf(
        endpointKey<ForwardCompatScreenResource>("GET") to
            EndpointFacts(
                summary = "A screen carrying a component no client can render (development only)",
                kind = EndpointKind.SCREEN,
                successBodyRef = WireSchema.PROFILE_COMPONENT,
            ),
        endpointKey<ArriveResource>("POST") to
            EndpointFacts(
                summary = "Start a dormant roaming package, as a first attach abroad would (development only)",
                // 202 rather than 200, and the document says so: the event goes to the broker and the
                // consumer polls, so the package is not started by the time this answers.
                successStatus = 202,
                successContentType = null,
            ),
        endpointKey<FailingResource>("GET") to
            EndpointFacts(
                summary = "Fail on purpose, so a crash report has something to report (development only)",
                // NO SUCCESS, and the document says so by describing the only thing it does. There is
                // no shape of answer here that is not the refusal below.
                successStatus = 500,
                successContentType = "application/json",
                successBodyType = "io.konekt.domain.ApiError",
            ),
    )
