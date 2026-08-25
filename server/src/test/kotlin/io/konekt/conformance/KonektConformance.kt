package io.konekt.conformance

import io.konekt.feature.auth.shared.api.AuthOtp
import io.konekt.feature.auth.shared.api.AuthSession
import io.konekt.feature.esim.shared.api.EsimWizardResource
import io.konekt.feature.purchase.shared.api.OrderScreen
import io.konekt.feature.purchase.shared.api.Purchases
import io.konekt.feature.realtime.shared.api.RealtimeStream
import io.konekt.openapi.endpointKey

// WHAT KONEKT DECLARES ABOUT ITS OWN CONFORMANCE COVERAGE.
//
// Two lists, both of them admissions rather than settings, and both re-derived by the gate on every
// run so that neither can go on being true after it stops being true. They are long today, and that
// length IS the finding: a `check(report.isClean)` over this deployment would be green while the
// walk reached three of the fifteen endpoints the server serves and five of the eleven checks saw
// nothing whatever.
//
// NO ADDRESS IS WRITTEN AS A STRING HERE, for the reason the whole repository keeps that rule: a
// path spelled outside a `*-shared-api` module is a second spelling of the contract, and a renamed
// segment becomes a silent mismatch instead of a compile error. The keys are asked of the same
// `@Resource` classes the routing tree is built from, through `endpointKey`. The two exceptions are
// the two the OpenAPI generator already had to make for the same reason: `/health` has no
// `@Resource`, and the SSE address cannot be one.

// A check that this deployment offers no target at all, and why.
//
// EACH LINE IS A GAP, NOT AN EXEMPTION. The distinction matters, because a completeness guard whose
// exemptions say "covered by a neighbour" hides exactly what it was built to show: none of these say
// that. Each says which surface konekt does not serve, so the entry disappears by itself the day the
// surface appears — the gate fails on a declared check that has acquired targets just as loudly as
// on an undeclared one that has lost them.
val KONEKT_CHECKS_WITH_NOTHING_TO_VISIT: Map<String, String> =
    mapOf(
        "form-fields" to
            "this server serves no endpoint of kind \"form\". The eSIM flow takes wizard-core only and draws " +
            "its own chrome, because kompot-wizard's WizardScreenComponent presupposes a FormSchema it " +
            "does not have (research-architecture §1.12), so no route answers a schema-plus-screen pair.",
        "etag" to
            "no operation declares 304. Conditional delivery is not implemented: the screens are built per " +
            "request and nothing computes an entity tag for them, so there is no revalidation to check.",
        "navigation" to
            "this server serves no endpoint of kind \"graph\". kompot-navigation's server-driven route graph " +
            "is not part of this build — the client's navigation is its own.",
        "updates" to
            "the check reads a RECORDING of an update stream from TckConfig.recordedUpdateStreams and never " +
            "opens a connection. Nothing records one, because recording it needs a run against a stand " +
            "and there is no conformance run at all yet.",
        "idempotency" to
            "it needs a state-changing endpoint declaring both 400 and 409 together with a body to POST, and " +
            "it performs a REAL operation. Neither of konekt's two `submit` endpoints declares 400, no " +
            "payload is supplied, and TckWalkPlan.allowStateChangingChecks is off — so the check returns " +
            "before its counter exists.",
    )

// Every endpoint the walk will not look at, with the kit's own reason. Exact, and it fails in both
// directions: an endpoint that becomes reachable has to leave this list, and a new endpoint nothing
// reaches has to be put on it by a person.
val KONEKT_UNWALKED_ENDPOINTS: Set<String> =
    setOf(
        // Not part of the product's API surface at all: it answers two letters of text/plain so a
        // supervisor can ask the process a question, and a blind walk parses one JSON document.
        "GET /health",
        // A stream. The kit checks it from a recording, and there is none — see the `updates` entry
        // above.
        "GET ${RealtimeStream.PATH}",
        // Addressed by naming a thing. Without an order id in TckConfig.pathParameters these have no
        // walkable address, and the order screen is the largest tree this server emits — which is
        // precisely the "green because it skipped the hardest screen" case the kit warns about.
        endpointKey<Purchases.ById>("GET"),
        endpointKey<OrderScreen>("GET"),
        endpointKey<Purchases.ById.Confirm>("POST"),
        // A blind walk is GET only. These change state, and what to send is the application's
        // domain rather than anything the kit can invent.
        endpointKey<AuthOtp.Request>("POST"),
        endpointKey<AuthOtp.Verify>("POST"),
        endpointKey<AuthSession.Refresh>("POST"),
        endpointKey<AuthSession.Logout>("POST"),
        endpointKey<Purchases>("POST"),
        endpointKey<EsimWizardResource>("POST"),
        endpointKey<EsimWizardResource.Step>("POST"),
    )
