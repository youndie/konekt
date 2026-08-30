---
id: api-openapi
title: The OpenAPI document
type: api_endpoints
status: active
services:
  - konekt-server
contract_source:
  - konekt:server `server/src/main/kotlin/io/konekt/openapi/` — generated from the routing tree
  - konekt:docs `docs/api/openapi.json` — the committed copy, compared on every build
---

# API: the OpenAPI document

> This document is about a **file**, not about a group of routes. The routes themselves are
> [endpoint-auth](endpoint-auth.md), [endpoint-purchase](endpoint-purchase.md),
> [endpoint-home](endpoint-home.md), [endpoint-esim-wizard](endpoint-esim-wizard.md) and
> [endpoint-health](endpoint-health.md). What is here is why `docs/api/openapi.json` exists, what in
> it is derived and what is asserted, and which parts of it are deliberately incomplete.

`docs/api/openapi.json` is a **build artefact**. `kompot-tck` walks a running server and reads the
endpoint kinds out of the deployment's OpenAPI document; it assumes no addresses, which is what lets
the same checks run against an implementation on any stack
([research-architecture](../research/research-architecture.md) §1.10). Without the document there is
no walk. `B-24` turned the walk into a gate, and what that gate asserts is **coverage, before any
verdict** — see below.

## Where each part of it comes from

The split matters more than the format, because it decides which parts of the file can be wrong.

| Part of an operation | Where it comes from | Can it drift? |
|---|---|---|
| path | the Ktor routing tree, through `Route.path(OpenApiRoutePathFormat)` | no — the `@Resource` classes build it |
| HTTP method | the `HttpMethodRouteSelector` on the leaf | no |
| `security` | an `authenticate { }` above the node in the tree | no |
| path and query parameters | the selectors `ktor-server-resources` created from the resource class | no |
| `x-kompot-endpoint-kind` | `konektEndpointFacts` | **yes** |
| success status, content type, body `$ref` | `konektEndpointFacts` | **yes** |
| the refusals an endpoint can answer | `konektEndpointFacts`, plus 401 for a secured route and 500 for every route | **yes** |

The derived half is read in `server/src/main/kotlin/io/konekt/openapi/RouteInventory.kt`; the
declared half is `server/src/main/kotlin/io/konekt/openapi/EndpointFacts.kt`. The generator refuses
to build a document at all unless the two name **exactly the same set of endpoints** — a route served
and not described, or described and not served, stops the build with both lists printed. That is the
answer to "the declared half can drift": it can drift in what it says about a body, and it cannot
drift in which routes exist.

**The auth tier is derived and not declared, and that is the point of the whole exercise.** Before
`B-23` the tier of a route was readable only from the indentation of `routing { }` in
`Application.kt`. It is now a value — `AuthTier`, in the route table — and it reaches the document
through the routing tree Ktor built from it, so no operation's `security` can disagree with the
server.

## What guards it

| Guard | What breaks it |
|---|---|
| `openApiDocument` itself | a route added, removed or renamed without a line in `konektEndpointFacts` |
| `OpenApiDocumentTest` — "matches what is committed" | anything at all changing without the file being re-recorded |
| `OpenApiDocumentTest` — the operation count | a route quietly leaving the surface |
| `OpenApiDocumentTest` — "the development flag adds exactly one route" | `DEV_REVEAL_OTP` changing the surface by more than `GET /api/v1/dev/otp` |
| `OpenApiDocumentTest` — "the way in is public…" | a screen route moving into the public group |
| `CompositionRootRoutesTest` | a route registered directly in `routing { }` instead of through the table |

Both were proved to bite by breaking them: removing `homeRoutes()` from the table failed with
`described and not served: GET /api/v1/screens/home`, and adding `homeRoutes()` straight into
`routing { }` failed with `expected: <[mountKonektRoutes]> but was: <[homeRoutes, mountKonektRoutes]>`.

## What the conformance gate asserts about it

`server/src/test/kotlin/io/konekt/conformance/` reads this file the way `kompot-tck` reads it and
asks what a walk of this deployment would have to visit. **Per check and per endpoint, never as a
sum.** A sum is satisfied by the six checks that have targets here while the other five see nothing
at all, and a `check(report.isClean)` over an empty walk is green — which is the whole of `B-24`.

| Guard | What breaks it |
|---|---|
| `assertEveryCheckHasSomethingToVisit` | a route leaving this document takes a check's last target with it — removing `/api/v1/screens/history/page` fails naming `pagination` |
| the same function, other direction | a check declared to have nothing to visit acquires a target and the declaration is not deleted |
| `assertNothingIsSkippedSilently` | an endpoint stops being reached, or a new one arrives that nothing reaches |
| `assertTheWalkVisitedEveryTarget` | a run visits fewer targets than the document offers — called from `e2e TckWalkTest`, which is what `B-24` added |

Two things this makes visible that no verdict would. **Five of the kit's eleven checks find nothing to
visit here** — `form-fields` and `navigation` because this server serves no `form` or `graph`
endpoint, `etag` because no operation declares 304, `updates` because the check reads a recording of
the stream and nothing records one, `idempotency` because neither `submit` endpoint declares 400.
And **the walk reaches three of the fifteen endpoints**: the two `{orderId}` screens need an
identifier the kit cannot invent, and the order screen is the largest tree this server emits. Each of
those is written down in `KonektConformance.kt` with the reason, and each is re-derived on every run
rather than trusted.

## Re-recording it

The document is committed and compared, the same arrangement as the wire schemas in `:shared:spec`
and for the same reason: **a diff in a pull request is the only place a contract change is noticed by
a person**. It is regenerated rather than edited — a hand-edit is overwritten by the next recording
and, until then, fails the build.

```bash
make openapi
```

It must run **on the Mac**. This repository is a one-way mutagen replica, so a file written on the
Linux box is reverted on the next sync and the run looks like it did nothing.

## What is deliberately not in it

An admitted gap costs a reader nothing; an invented detail costs them the whole file. Four of them:

- **Request bodies.** No operation declares one. Describing them means either hand-writing a copy of
  each Kotlin data class — stale within a sprint, and the exact thing this repository refuses — or
  generating them from the `kotlinx.serialization` descriptors the way `kompot-spec` does for the
  wire types. The second is the right answer and it is not `B-23`'s size. The conformance kit does not
  read request bodies: what to POST is `TckConfig.submitPayloads`, which is the application's to
  supply.
- **The schemas of our own response DTOs.** `RequestOtpResponse`, `PurchaseOrderResponse` and
  `DevOtpResponse` appear as an untyped object naming their Kotlin type. Only bodies the committed
  wire specification really describes carry a `$ref`, because the kit validates a response against
  whatever the ref points at — a ref to a schema nobody publishes would be a finding about the server
  rather than about the document.
- **The development route.** `GET /api/v1/dev/otp` exists only when `DEV_REVEAL_OTP=true`, and the
  committed document describes a production deployment. It is still described in code
  (`devOtpEndpointFacts`), and the test builds the development document too, so that description
  cannot rot unnoticed.
- **Which refusals each endpoint can produce, exhaustively.** Each entry lists the refusals read in
  that route and the use case behind it; nothing in the build can prove such a list is complete, and
  a list that is nearly right reads exactly like one that is.

## The kompot endpoint kinds this server serves

The vocabulary was read in `kompot-tck` 0.31.0.74 rather than recalled, and is written down once in
`server/src/main/kotlin/io/konekt/openapi/EndpointKind.kt`.

| Kind | Endpoints | Why |
|---|---|---|
| `screen` | the three `/api/v1/screens/…` GETs and both eSIM wizard POSTs | they answer a `KompotComponent` tree |
| `page` | `GET /api/v1/screens/history/page` | it answers a `KompotPageResponse` |
| `submit` | `POST /api/v1/auth/otp/verify`, `POST /api/v1/auth/session/refresh` | they answer a `KompotAction` (`update_session`) |
| `updates_stream` | `GET /api/v1/realtime` | a `text/event-stream` of `UpdateComponentMessage` frames |
| *(none)* | `/health`, the OTP request, logout, the three purchase routes, the dev route | they serve no kompot vocabulary |

**An endpoint with no kind is not an omission.** The kit reads a missing extension as `"unknown"`,
claims it with no check, and says so under "Not walked" in its report. Borrowing the nearest-looking
word would be worse: a kind asserts the *shape* of the body, and a wrong one turns a conformant
server into a page of findings about a contract it never made. The two wizard POSTs are where that
bites hardest — neither `submit` (which asserts a `KompotAction`) nor `wizard_resume` (which asserts
kompot-wizard's `WizardResumeRequest`) is true of them, because this flow takes `wizard-core` only
and draws its own chrome (research-architecture §1.12). They answer a component tree, so they are
`screen`, on a POST.
