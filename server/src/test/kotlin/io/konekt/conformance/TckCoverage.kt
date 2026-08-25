package io.konekt.conformance

import io.konekt.openapi.EndpointKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

// THE GATE IS ON COVERAGE, AND IT COMES BEFORE THE VERDICT.
//
// `kompot-tck` says it about itself, in `TckRunner.exercising`: "a check that found no matching
// endpoint passes silently, which is the commonest way to end up with a useless conformance kit".
// `check(report.isClean)` — what the readme shows and what everybody writes — is green on a server
// the walk never reached. It is correct and it is not sufficient, because a verdict over an empty set
// is not a verdict.
//
// So this file answers one question, per check and never as a total: **what would the walk have to
// visit here**. A sum across the eleven checks is satisfied by the six that do have targets on this
// deployment while the other five see nothing at all — which is not a hypothetical shape, it is what
// `KONEKT_CHECKS_WITH_NOTHING_TO_VISIT` records about this server today.
//
// WHAT IS TRANSCRIBED AND WHY. Everything below marked "kompot-tck 0.31.0.74" was read in that
// version's sources — `TckRunner.kt` and `TckEndpoint.kt`, the artefact this build's BOM pins — and
// not recalled. It is a SECOND OPINION about the kit's target selection, which is a real cost: the
// kit widens a check and this file does not follow. Two things pay for it. It answers before a run
// rather than after one, so the gate needs no stand and no seeded database to say that a screen left
// the surface; and the transcription is deleted the moment `kompot-tck` is on the test classpath —
// `conformanceEndpoints` becomes `TckEndpoints.fromOpenApi` and `tckTargets` is held against the
// runner's own `report.exercised` by `assertTheWalkVisitedEveryTarget` below.

// The version the transcription was read in. It is the version `libs.versions.toml` pins for the
// whole kompot platform, and it is spelled here so a bump makes somebody re-read this file.
const val TCK_VERSION: String = "0.31.0.74"

// The kit's default for an operation carrying no `x-kompot-endpoint-kind`: not an error, and no check
// claims such an endpoint (`TckEndpoints.fromOpenApi`).
const val UNKNOWN_KIND: String = "unknown"

// Two kinds this deployment serves nowhere and the kit still selects on. Named here rather than in
// `EndpointKind`, which deliberately lists only the vocabulary konekt actually answers.
private const val FORM_KIND = "form"
private const val GRAPH_KIND = "graph"

// The kinds `idempotencyContract` treats as state-changing (`TckRunner.STATE_CHANGING_KINDS`).
private val STATE_CHANGING_KINDS = setOf(EndpointKind.SUBMIT, "wizard_resume")

// One endpoint as the kit reads it out of an OpenAPI document. A transcription of `TckEndpoint`,
// carrying only the fields the selection predicates below actually ask about.
data class ConformanceEndpoint(
    val method: String,
    val path: String,
    val kind: String,
    val secured: Boolean,
    val successContentType: String?,
    val statuses: Set<Int>,
    val deprecated: Boolean,
) {
    // How the walk records that it reached this endpoint: method and path together, because one path
    // carries a GET and a POST with entirely different contracts.
    val key: String get() = "$method $path"

    val placeholders: List<String> get() = PLACEHOLDER.findAll(path).map { it.groupValues[1] }.toList()

    val hasPathParameters: Boolean get() = '{' in path

    // A stream is a sequence of frames rather than one document, so it stays out of every blind GET.
    val respondsWithJson: Boolean get() = successContentType == "application/json"

    private companion object {
        val PLACEHOLDER = Regex("""\{([^}]+)}""")
    }
}

// The document, read the way the kit reads it. A transcription of `TckEndpoints.fromOpenApi`,
// including the one thing it warns about in its own comment: the PRESENCE of `security` is not the
// question — an absent key inherits the document's requirement and `security: []` means public.
fun conformanceEndpoints(document: JsonObject): List<ConformanceEndpoint> {
    val documentSecurity = document["security"] as? JsonArray
    val paths = document["paths"] as? JsonObject ?: error("the OpenAPI document has no paths")

    return paths.flatMap { (path, operations) ->
        operations.jsonObject.map { (method, operation) ->
            val json = operation.jsonObject
            val responses = json.getValue("responses").jsonObject
            val statuses = responses.keys.mapNotNull { it.toIntOrNull() }.toSet()
            val success =
                statuses.filter { it in 200..299 }.minOrNull()
                    ?: error("$method $path: no success status")
            val security = json["security"] as? JsonArray ?: documentSecurity
            val successResponse = responses.getValue(success.toString()).jsonObject

            ConformanceEndpoint(
                method = method.uppercase(),
                path = path,
                kind = (json[EndpointKind.EXTENSION] as? JsonPrimitive)?.content ?: UNKNOWN_KIND,
                secured = security != null && security.isNotEmpty(),
                successContentType = (successResponse["content"] as? JsonObject)?.keys?.firstOrNull(),
                statuses = statuses,
                deprecated = (json["deprecated"] as? JsonPrimitive)?.content == "true",
            )
        }
    }
}

// What konekt hands the kit, reduced to the part that decides COVERAGE.
//
// The kit cannot invent an identifier that exists, a body to POST or a captured stream, so four of
// its eleven checks reach nothing at all unless the application supplies them (`TckConfig`). Those
// supplies are what this plan names — the addresses, never the values, because a value is only ever
// correct against a running deployment and this file never has one.
data class TckWalkPlan(
    // `TckConfig.loginPath`. The kit records "POST <loginPath>" as visited even though no counter
    // covers it: `authenticate` is a precondition of the other checks and not a check itself.
    val loginPath: String?,
    // `TckConfig.pathParameters`, by declared address: which placeholders have a value. An endpoint
    // with a placeholder nobody filled in has no walkable address and drops out of every blind GET.
    val pathParameters: Map<String, Set<String>>,
    // `TckConfig.recordedUpdateStreams`. The one kind a blind walk cannot reach.
    val recordedUpdateStreams: Set<String>,
    // `TckConfig.submitPayloads`. What to POST is the application's domain.
    val submitPayloads: Set<String>,
    // `TckConfig.allowStateChangingChecks`. False is not a smaller number in one counter: the check
    // returns before its counter is ever recorded, so `idempotency` is ABSENT from `exercised`
    // rather than zero — which is the one way a per-check floor written as `counts.all { it > 0 }`
    // reads a check that never ran as a check that passed.
    val allowStateChangingChecks: Boolean,
)

// WHAT KONEKT SUPPLIES TODAY, WHICH IS NOTHING, AND THAT IS THE HONEST VALUE OF THIS FILE.
//
// There is no `TckGate` yet — running the kit needs `kompot-tck` on `:server`'s test classpath, and
// B-24 could not add it (see the item). Until then the plan is empty, the walk reaches three of the
// fifteen endpoints this server serves, and the declarations below say so out loud instead of a
// green tick saying nothing. Every entry here moving from empty to non-empty makes the gate stricter
// on its own: the checks it unlocks leave `KONEKT_CHECKS_WITH_NOTHING_TO_VISIT` and the endpoints it
// unlocks leave `KONEKT_UNWALKED_ENDPOINTS`, and the gate fails until both lists are corrected.
val KONEKT_WALK_PLAN =
    TckWalkPlan(
        loginPath = null,
        pathParameters = emptyMap(),
        recordedUpdateStreams = emptySet(),
        submitPayloads = emptySet(),
        allowStateChangingChecks = false,
    )

// One check of the kit: the counter it records, and what it selects to visit.
data class TckCheck(
    // The key it writes into `TckReport.exercised`.
    val name: String,
    // What it selects, in the kit's terms, so a failure message can say what went missing rather
    // than only that something did.
    val claims: String,
    val targets: (List<ConformanceEndpoint>, TckWalkPlan) -> List<ConformanceEndpoint>,
)

// Every counter `TckRunner` records, in the order `run()` calls them.
//
// `authenticate` is deliberately absent: it writes findings under the name "auth" and records no
// counter, because it is a precondition rather than a check.
val TCK_CHECKS: List<TckCheck> =
    listOf(
        TckCheck(
            name = "auth-required",
            claims = "a walkable GET that is secured",
        ) { endpoints, plan -> plan.probeable(endpoints).filter { it.secured } },
        TckCheck(
            name = "schema",
            claims = "every walkable GET",
        ) { endpoints, plan -> plan.probeable(endpoints) },
        TckCheck(
            name = "component-id",
            claims = "every walkable GET",
        ) { endpoints, plan -> plan.probeable(endpoints) },
        TckCheck(
            name = "form-fields",
            claims = "a walkable GET of kind \"$FORM_KIND\"",
        ) { endpoints, plan -> plan.probeable(endpoints).filter { it.kind == FORM_KIND } },
        TckCheck(
            name = "etag",
            claims = "a walkable GET whose description declares 304",
        ) { endpoints, plan -> plan.probeable(endpoints).filter { 304 in it.statuses } },
        TckCheck(
            name = "pagination",
            claims = "a walkable GET of kind \"${EndpointKind.PAGE}\"",
        ) { endpoints, plan -> plan.probeable(endpoints).filter { it.kind == EndpointKind.PAGE } },
        TckCheck(
            name = "navigation",
            claims = "a walkable GET of kind \"$GRAPH_KIND\"",
        ) { endpoints, plan -> plan.probeable(endpoints).filter { it.kind == GRAPH_KIND } },
        TckCheck(
            name = "perform",
            claims = "every walkable GET",
        ) { endpoints, plan -> plan.probeable(endpoints) },
        TckCheck(
            name = "updates",
            claims = "kind \"${EndpointKind.UPDATES_STREAM}\" with a recording in TckConfig.recordedUpdateStreams",
        ) { endpoints, plan ->
            endpoints.filter { it.kind == EndpointKind.UPDATES_STREAM && it.path in plan.recordedUpdateStreams }
        },
        TckCheck(
            name = "text-spans",
            claims = "every walkable GET",
        ) { endpoints, plan -> plan.probeable(endpoints) },
        TckCheck(
            name = "idempotency",
            claims = "a state-changing endpoint declaring both 400 and 409, with a body in TckConfig.submitPayloads",
        ) { endpoints, plan ->
            if (!plan.allowStateChangingChecks) {
                emptyList()
            } else {
                endpoints.filter {
                    it.kind in STATE_CHANGING_KINDS &&
                        400 in it.statuses &&
                        409 in it.statuses &&
                        it.path in plan.submitPayloads
                }
            }
        },
    )

// A blind GET applies only to an address with no unfilled placeholder that answers one JSON
// document. `TckRunner.probeable`, verbatim except that a resolved address here is a boolean rather
// than a string — the values live in the plan's other half, which this file never has.
private fun TckWalkPlan.probeable(endpoints: List<ConformanceEndpoint>): List<ConformanceEndpoint> =
    endpoints.filter { it.method == "GET" && !it.deprecated && it.respondsWithJson && walkable(it) }

private fun TckWalkPlan.walkable(endpoint: ConformanceEndpoint): Boolean =
    endpoint.placeholders.all { it in pathParameters[endpoint.path].orEmpty() }

// What each check would find to visit in this document, by check name. The value is the endpoint
// keys rather than a count, so a failure can print the addresses and not only how many there were.
fun tckTargets(
    endpoints: List<ConformanceEndpoint>,
    plan: TckWalkPlan = KONEKT_WALK_PLAN,
): Map<String, List<String>> =
    TCK_CHECKS.associate { check ->
        check.name to
            check.targets(endpoints, plan).map { it.key }
    }

// An endpoint no check will look at, and why — `TckRunner.notWalked`, transcribed with its reasons.
//
// THIS IS THE SECOND SUBJECT, and the kit's own comment on `TckSkip` says why one is not enough:
// the per-check counters "answer 'did this check have targets', and the other endpoints keep every
// check busy while one is quietly left out. A run that is green because it skipped the hardest
// screen is the failure this closes."
data class TckSkipped(
    val key: String,
    val reason: String,
) {
    override fun toString(): String = "$key ($reason)"
}

fun tckSkipped(
    endpoints: List<ConformanceEndpoint>,
    plan: TckWalkPlan = KONEKT_WALK_PLAN,
): List<TckSkipped> {
    // Everything a check claims, plus the login, which `authenticate` marks visited without a
    // counter. A `graph` route can also mark its target visited at run time — this build serves no
    // endpoint of that kind, which the `navigation` entry of the coverage gate is what asserts.
    val visited =
        tckTargets(endpoints, plan).values.flatten().toSet() +
            setOfNotNull(plan.loginPath?.let { "POST $it" })

    return endpoints
        .filterNot { it.key in visited }
        .map { endpoint -> TckSkipped(endpoint.key, plan.reasonNotWalked(endpoint)) }
        .sortedBy { it.key }
}

// Why an endpoint was left out, in the reader's terms rather than in the kit's — `TckRunner.notWalked`,
// reason for reason. The ORDER of the branches is the kit's own and is load-bearing: a POST carrying
// a placeholder is reported against the placeholder, because that is the thing a person can supply.
private fun TckWalkPlan.reasonNotWalked(endpoint: ConformanceEndpoint): String =
    when {
        endpoint.deprecated -> {
            "declared deprecated"
        }

        endpoint.hasPathParameters -> {
            "no value in TckConfig.pathParameters for the placeholders of \"${endpoint.path}\""
        }

        endpoint.kind == EndpointKind.UPDATES_STREAM -> {
            "no recorded stream for it in TckConfig.recordedUpdateStreams"
        }

        !endpoint.respondsWithJson -> {
            "the response is ${endpoint.successContentType ?: "not declared"}, not one JSON document"
        }

        endpoint.method != "GET" && endpoint.kind == EndpointKind.SUBMIT && !allowStateChangingChecks -> {
            "state-changing checks are switched off"
        }

        endpoint.method != "GET" && endpoint.kind == EndpointKind.SUBMIT -> {
            "no body for it in TckConfig.submitPayloads"
        }

        endpoint.method != "GET" -> {
            "only GET endpoints are walked blind"
        }

        else -> {
            "no check claims it"
        }
    }

// ---- the gate ----------------------------------------------------------------------------------

// THE FIRST ASSERTION OF THE GATE, and nothing about a verdict may run before it.
//
// A check with no target is not a check that passed. It is either a deployment that lost the surface
// the check exists for — the failure this is written to catch — or a deployment that never had it,
// which is a fact about konekt and belongs in `declaredEmpty` with a sentence a reviewer reads.
//
// The declaration cannot rot quietly, because it is re-derived rather than trusted: a check listed
// there that DOES have targets fails just as loudly as one that is missing from it. What it cannot
// prevent is somebody answering a red gate by adding a line — which is why the reason is prose and
// not a flag.
fun assertEveryCheckHasSomethingToVisit(
    targets: Map<String, List<String>>,
    declaredEmpty: Map<String, String>,
) {
    val known = TCK_CHECKS.map { it.name }.toSet()
    val problems = mutableListOf<String>()

    (targets.keys - known).sorted().forEach {
        problems +=
            "$it — a counter no entry of TCK_CHECKS names; the transcription of kompot-tck $TCK_VERSION is out of date"
    }
    (known - targets.keys).sorted().forEach {
        problems += "$it — no target list was computed for this check at all"
    }
    (declaredEmpty.keys - known).sorted().forEach {
        problems += "$it — declared as having nothing to visit, and the kit records no counter under that name"
    }

    TCK_CHECKS.forEach { check ->
        val visits = targets[check.name] ?: return@forEach
        val declared = declaredEmpty[check.name]
        when {
            visits.isEmpty() && declared == null -> {
                problems += "${check.name} — visits nothing. It claims ${check.claims}, and this deployment offers none"
            }

            visits.isNotEmpty() && declared != null -> {
                problems +=
                    "${check.name} — declared as having nothing to visit (\"$declared\"), and it now has " +
                    "${visits.size}: ${visits.joinToString()}. Delete the entry"
            }
        }
    }

    if (problems.isNotEmpty()) {
        throw AssertionError(
            buildString {
                append("the conformance walk is vacuous for ")
                append(problems.size)
                append(" check(s), and a verdict over an empty set is not a verdict:")
                problems.forEach { append("\n  ").append(it) }
                append(
                    "\nEither the deployment lost the surface a check exists for — read the diff of " +
                        "docs/api/openapi.json — or the check belongs in KONEKT_CHECKS_WITH_NOTHING_TO_VISIT " +
                        "with a reason worth reading.",
                )
            },
        )
    }
}

// THE SECOND SUBJECT: an endpoint nobody looks at. Exact set rather than a floor, in both
// directions — an endpoint that becomes walkable has to leave the list, and a new endpoint nothing
// walks has to be put on it deliberately, in front of a reviewer, with the kit's own reason beside
// it.
fun assertNothingIsSkippedSilently(
    skipped: List<TckSkipped>,
    declared: Set<String>,
) {
    val actual = skipped.associateBy { it.key }
    val unexpected = (actual.keys - declared).sorted()
    val stale = (declared - actual.keys).sorted()
    if (unexpected.isEmpty() && stale.isEmpty()) return

    throw AssertionError(
        buildString {
            append("the set of endpoints the conformance walk never looks at is not the declared one.")
            if (unexpected.isNotEmpty()) {
                append("\n  walked by nothing and not declared:")
                unexpected.forEach { append("\n    ").append(actual.getValue(it)) }
            }
            if (stale.isNotEmpty()) {
                append("\n  declared unwalked and now walked (delete the line):")
                stale.forEach { append("\n    ").append(it) }
            }
        },
    )
}

// THE OTHER HALF OF THE GATE, AND IT HAS NO PRODUCTION CALLER YET — say so rather than let a green
// suite imply otherwise. Its caller is the `TckGate` that runs the kit, which needs
// `testImplementation(libs.kompot.tck)` in `server/build.gradle.kts`; B-24 could not write that file
// and the item records it. The whole of that caller is:
//
//     val report = TckRunner(RemoteTckTransport(url, client), konektTckConfig()).run()
//     assertTheWalkVisitedEveryTarget(tckTargets(endpoints, KONEKT_WALK_PLAN), report.exercised)
//     assertTrue(report.isClean, report.toString())   // the verdict, and only now
//
// Counts, not booleans: a check that visited one of four screens is a check that reported on one of
// four screens, and `> 0` calls that coverage. ABSENT is treated apart from zero on purpose —
// `idempotencyContract` returns before its counter exists, so an absent key is a check that never
// ran and a zero is a check that ran and found nothing.
fun assertTheWalkVisitedEveryTarget(
    targets: Map<String, List<String>>,
    exercised: Map<String, Int>,
) {
    val problems = mutableListOf<String>()

    // An empty target list is the coverage gate's business, not this one's: it has already been
    // held against the declaration of what this deployment cannot feed.
    targets.filterValues { it.isNotEmpty() }.forEach { (check, visits) ->
        val count = exercised[check]
        if (count == null) {
            problems +=
                "$check — the run recorded no counter at all, so the check never ran, " +
                "and ${visits.size} target(s) were waiting for it"
        } else if (count < visits.size) {
            problems += "$check — visited $count of ${visits.size}: ${visits.joinToString()}"
        }
    }

    if (problems.isNotEmpty()) {
        throw AssertionError(
            buildString {
                append("the conformance run did not visit what this deployment offers it:")
                problems.forEach { append("\n  ").append(it) }
                append("\nThe verdict was not read: a check that saw nothing cannot have found anything.")
            },
        )
    }
}
