package io.konekt.openapi

import io.ktor.server.routing.RoutingNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// The description of this deployment, in the one form the conformance kit can read.
//
// `kompot-tck` walks a RUNNING server and reads endpoint kinds out of an OpenAPI document; it
// assumes no addresses, which is what lets the same checks run against an implementation on any
// stack (research-architecture §1.10). Without the document there is no walk, so this is a build
// artefact rather than documentation that happens to be machine-readable.
//
// It is GENERATED FROM THE ROUTING TREE, not written. `RouteInventory` walks the tree Ktor built out
// of the `@Resource` classes, so a renamed segment, a new route or a route that moved between tiers
// changes this document without anybody remembering to. What cannot be derived — the status, the
// body, the kompot kind — is `konektEndpointFacts`, and the two are held against each other below.

const val OPENAPI_VERSION = "3.1.0"

// The bearer scheme's name, used by every secured operation and defined once under components.
private const val BEARER_SCHEME = "bearerAuth"

// A document that lists a route the server does not serve, or omits one it does, is worse than no
// document at all — the walk would then report findings about addresses nobody serves and skip the
// screens that matter. This is where that cannot happen: the two halves are compared by key, and a
// difference in either direction stops the build with both lists printed.
fun openApiDocument(
    root: RoutingNode,
    facts: Map<String, EndpointFacts> = konektEndpointFacts,
): JsonObject {
    val inventory = routeInventory(root)
    val served = inventory.map { it.key }.toSet()
    val described = facts.keys

    val undescribed = (served - described).sorted()
    val imaginary = (described - served).sorted()
    require(undescribed.isEmpty() && imaginary.isEmpty()) {
        buildString {
            append("the OpenAPI document and the routing tree disagree.")
            if (undescribed.isNotEmpty()) {
                append("\n  served and not described (add them to konektEndpointFacts): ")
                append(undescribed.joinToString("\n    ", prefix = "\n    "))
            }
            if (imaginary.isNotEmpty()) {
                append("\n  described and not served (the route is gone, or never existed): ")
                append(imaginary.joinToString("\n    ", prefix = "\n    "))
            }
        }
    }

    return buildJsonObject {
        put("openapi", OPENAPI_VERSION)
        putJsonObject("info") {
            put("title", "konekt — subscriber account")
            // The version of the API SURFACE, which is the one version this generator can read out
            // of the code: every address below carries it. It is deliberately not the build's
            // version — that would be a number this file cannot check and would silently rot.
            put("version", "v1")
            put(
                "description",
                "Generated from the routing tree by io.konekt.openapi — never hand-edited. It describes a " +
                    "PRODUCTION deployment: the development route that reads back a one-time code exists " +
                    "only when DEV_REVEAL_OTP=true and is deliberately absent here. Request bodies are not " +
                    "described; see docs/api/api-openapi.md for that gap and why it is admitted rather " +
                    "than filled with hand-written copies of the Kotlin types.",
            )
        }
        putJsonObject("paths") {
            inventory.groupBy { it.path }.toSortedMap().forEach { (path, entries) ->
                putJsonObject(path) {
                    entries.sortedBy { it.method }.forEach { entry ->
                        put(entry.method.lowercase(), operation(entry, facts.getValue(entry.key)))
                    }
                }
            }
        }
        putJsonObject("components") {
            putJsonObject("securitySchemes") {
                putJsonObject(BEARER_SCHEME) {
                    put("type", "http")
                    put("scheme", "bearer")
                    put("bearerFormat", "JWT")
                    put(
                        "description",
                        "The access token from update_session. It carries its session family, and the " +
                            "provider refuses a revoked one — so logout takes effect at once.",
                    )
                }
            }
            putJsonObject("schemas") {
                put("ApiError", apiErrorSchema())
            }
        }
    }
}

private fun operation(
    entry: RouteEntry,
    facts: EndpointFacts,
): JsonObject =
    buildJsonObject {
        put("summary", facts.summary)
        facts.kind?.let { put(EndpointKind.EXTENSION, it) }

        // ALWAYS PRESENT, and per operation rather than inherited from the document. The kit reads
        // an absent `security` as "inherits the document's", and `security: []` as public — so an
        // explicit empty array on a public operation is the standard way to say what this server
        // means, and leaving the key out on one of them would make the way in look secured.
        put("security", security(entry.secured))

        val parameters = parameters(entry)
        if (parameters.isNotEmpty()) put("parameters", JsonArray(parameters))

        putJsonObject("responses") {
            put(facts.successStatus.toString(), successResponse(facts))
            refusalsOf(entry, facts).forEach { status ->
                put(status.toString(), refusalResponse(status))
            }
        }
    }

private fun security(secured: Boolean): JsonArray =
    if (secured) {
        buildJsonArray { add(buildJsonObject { put(BEARER_SCHEME, JsonArray(emptyList())) }) }
    } else {
        JsonArray(emptyList())
    }

private fun parameters(entry: RouteEntry): List<JsonObject> =
    entry.pathParameters.map { name ->
        buildJsonObject {
            put("name", name)
            put("in", "path")
            put("required", true)
            putJsonObject("schema") { put("type", "string") }
        }
    } +
        entry.queryParameters.map { parameter ->
            buildJsonObject {
                put("name", parameter.name)
                put("in", "query")
                put("required", parameter.required)
                putJsonObject("schema") { put("type", "string") }
            }
        }

private fun successResponse(facts: EndpointFacts): JsonObject =
    buildJsonObject {
        put("description", facts.summary)
        val contentType = facts.successContentType
        if (contentType != null) {
            putJsonObject("content") {
                putJsonObject(contentType) {
                    putJsonObject("schema") {
                        // A `$ref` is the only shape the kit validates against, so it is written only
                        // where the wire specification really describes the body. Everything else is
                        // an untyped object that names its Kotlin type in the description — which the
                        // kit reads as "no schema declared" and leaves alone, honestly.
                        if (facts.successBodyRef != null) {
                            put("\$ref", facts.successBodyRef)
                        } else {
                            put("type", "object")
                            facts.successBodyType?.let { put("description", it) }
                        }
                    }
                }
            }
        }
    }

// 401 on every secured route and 500 on every route are properties of the composition rather than of
// a handler: the JWT provider challenges, and StatusPages answers a bare 500 for anything it did not
// expect. Neither is written down beside an endpoint, because a list that has to be remembered on
// every new route is a list that is wrong on the third one.
private fun refusalsOf(
    entry: RouteEntry,
    facts: EndpointFacts,
): List<Int> = (facts.refusals + setOfNotNull(if (entry.secured) 401 else null) + 500).sorted()

private fun refusalResponse(status: Int): JsonObject =
    buildJsonObject {
        put("description", REFUSAL_DESCRIPTIONS.getValue(status))
        if (status == 429) {
            putJsonObject("headers") {
                putJsonObject("Retry-After") {
                    put("description", "Seconds to wait. A client told to slow down with no number picks one.")
                    putJsonObject("schema") { put("type", "string") }
                }
            }
        }
        putJsonObject("content") {
            putJsonObject("application/json") {
                putJsonObject("schema") { put("\$ref", "#/components/schemas/ApiError") }
            }
        }
    }

// The wording is KonektException's own, because that sealed hierarchy is where each of these is
// decided. A description invented here would be a second explanation of the same rule.
private val REFUSAL_DESCRIPTIONS: Map<Int, String> =
    mapOf(
        401 to "No session, or one that no longer means anything.",
        404 to
            "It does not exist, OR it exists and belongs to somebody else — deliberately the same " +
            "answer, because a 403 confirms existence and hands out an enumeration oracle.",
        409 to "The current state of the world refuses this, and retrying the same request will not help.",
        422 to "The request is well-formed and its contents are wrong.",
        429 to "Too many attempts. Retry-After carries the wait.",
        500 to
            "Something went wrong on our side. The body never carries the cause: an unexpected " +
            "exception's message is written for whoever wrote the code, and a subscriber is not " +
            "that reader.",
    )

private fun apiErrorSchema(): JsonObject =
    buildJsonObject {
        put("type", "object")
        put("description", "io.konekt.domain.ApiError — the one body every refusal answers with.")
        putJsonArray("required") {
            add(JsonPrimitive("code"))
            add(JsonPrimitive("message"))
        }
        putJsonObject("properties") {
            putJsonObject("code") { put("type", "string") }
            putJsonObject("message") { put("type", "string") }
        }
    }

// Where the committed copy lives and exactly how it is rendered.
//
// Committed and compared rather than generated on demand, the same arrangement as the wire schemas
// in :shared:spec: a diff in a pull request is the only place a contract change is ever noticed by a
// person.
object OpenApiFiles {
    // Relative to the module directory, which is what `server/build.gradle.kts` pins every Test
    // task's working directory to. Stated rather than assumed: a changed Gradle default would put
    // the document somewhere nobody looks and the comparison would happily record a new one.
    const val PATH = "../docs/api/openapi.json"

    // Recording is opt-in through the environment AND must run on the Mac: this repository is a
    // one-way mutagen replica, so a file written on the Linux side is reverted on the next sync and
    // the run looks like it did nothing. `make openapi` is that command; B-23 records why it is not
    // yet spelled `./gradlew :server:openApi`.
    const val RECORD_ENV = "KONEKT_OPENAPI_RECORD"

    private val json = Json { prettyPrint = true }

    fun render(document: JsonObject): String = json.encodeToString(JsonObject.serializer(), document) + "\n"

    fun recordMode(): Boolean = System.getenv(RECORD_ENV) == "true"
}
