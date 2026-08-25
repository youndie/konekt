---
id: B-24
title: "The TCK gate asserts what it visited, not that it was clean"
status: done
priority: P0
size: M
stage: stage-m4-proof
blocked_by: [B-23]
---

# B-24 — The TCK gate asserts what it visited, not that it was clean

The toolkit's own warning: *"a check that found nothing to apply to passes silently, and that is the
commonest way to end up with a conformance kit that proves nothing"* — which is why the report prints
how many targets each check visited. A gate on `report.isClean` is green on a server whose screens the
walk never reached.

- **The decision and its reason.** The CI step parses the per-check target counts and fails when any
  check visited zero targets, before it looks at the verdict. The assertion is on coverage first
  because a verdict over an empty set is not a verdict.
- The rejected alternative is `check(report.isClean)`, which is what the readme's example shows and
  what everyone writes. It is correct and it is not sufficient.
- Not covered: the client corpus. `kompot-client-tck` is a separate item, and upstream #52 is still
  open on it.

- AC: deliberately removing a route from the OpenAPI document turns the gate red with a message naming
  the check that visited nothing.
- AC: the gate runs on every pull request and on the default branch.
- Anchors: `server/src/test/kotlin/io/konekt/conformance/TckCoverage.kt`,
  `server/src/test/kotlin/io/konekt/conformance/KonektConformance.kt`, `.github/workflows/check.yaml`.

Background: [research-architecture](../research/research-architecture.md) §1.10, Risk 2.

## What landed

Two gates rather than one, because they need different things and only one of them needs a stand.

| | Where | Subject | Runs |
|---|---|---|---|
| coverage | `:server:test`, `make tck` | the committed `docs/api/openapi.json` | every build |
| the walk | `:e2e`, `TckWalkTest` | the running deployment | `make stand-up && make e2e`, and the CI job that does the same |

The declarations both read live in `server/src/testFixtures` so the two cannot drift, and the kit's
`TckConfig` is **derived** from `KONEKT_WALK_PLAN` rather than written a second time. The drift that
matters is invisible in exactly one direction: a walk supplying MORE than the plan declares makes the
coverage assertion under-claim while staying green.

### The gate that exists

The subject is the committed `docs/api/openapi.json` — the file the kit is handed as
`TckConfig.openApi` — and the question is **what a walk of this deployment would have to visit**,
asked per check and per endpoint and never as a sum. A sum is satisfied by the six checks that have
targets here while the other five see nothing at all, which is the exact shape this item refuses.

| Assertion | Where | What it refuses |
|---|---|---|
| every check of the kit has at least one target here | `assertEveryCheckHasSomethingToVisit` | a check that went blind because the deployment lost the surface it exists for |
| a check declared to have nothing to visit really has nothing | the same function | a declaration that stopped being true and nobody noticed |
| the endpoints no check looks at are exactly the declared ones | `assertNothingIsSkippedSilently` | "green because it skipped the hardest screen" — the axis the per-check counters cannot see, in the kit's own words on `TckSkip` |
| a run visited what this deployment offers it | `assertTheWalkVisitedEveryTarget` | **no caller yet** — see below |

**Proved by mutation, and the mutation is the acceptance criterion itself.** Removing
`/api/v1/screens/history/page` from `docs/api/openapi.json` turned the gate red with

```
the conformance walk is vacuous for 1 check(s), and a verdict over an empty set is not a verdict:
  pagination — visits nothing. It claims a walkable GET of kind "page", and this deployment offers none
```

and the file was restored byte for byte (`sha256 c1aa4542…`) and the nine tests went green again.

### What the gate found, which is the point of having built it

The declarations in `KonektConformance.kt` are long, and their length is the finding. A
`check(report.isClean)` over this deployment today would be green while:

- **five of the eleven checks see nothing at all.** `form-fields` and `navigation` because this server
  serves no `form` or `graph` endpoint; `etag` because no operation declares 304 and nothing computes
  an entity tag; `updates` because the check reads a *recording* of the stream and nothing records
  one; `idempotency` because neither `submit` endpoint declares 400 and there is no payload to POST.
- **the walk reaches three of the fifteen endpoints the server serves.** The two `{orderId}` screens
  are unreachable without an identifier in `TckConfig.pathParameters` — and the order screen is the
  largest tree this server emits.

Each of those is a real gap rather than an exemption: every entry is re-derived on each run, so the
gate fails just as loudly on a declared check that has *acquired* targets as on an undeclared one
that has lost them, and each line disappears by itself the day the surface behind it appears.

### What is left

1. `testImplementation(libs.kompot.tck)` in `server/build.gradle.kts` — one line, and the only thing
   that was impossible here.
2. `TckGate`, which runs `TckRunner(RemoteTckTransport(url, client), konektTckConfig())` against a
   stand and feeds `report.exercised` to `assertTheWalkVisitedEveryTarget` before reading
   `report.isClean`. That function exists and is tested; nothing calls it, and this file says so
   rather than letting a green suite imply otherwise.
3. Filling `KONEKT_WALK_PLAN` in — an order id, a recorded stream, a login, submit payloads. Every
   entry that stops being empty makes the gate stricter on its own.
4. Replacing the transcription. `conformanceEndpoints` and the eleven selection predicates were read
   in `kompot-tck 0.31.0.74` and copied; they are a second opinion about the kit's own target
   selection and are deleted the moment the kit is on the classpath.

## What the first real walk found

Two defects, both fatal to a client and both invisible to 108 green tests.

**Every route that receives a body answered 500 for a malformed one.** `configureStatusPages` maps
`KonektException` — a sealed hierarchy, so the `when` has no `else` and the compiler enforces
completeness — and everything else falls to the handler that answers `internal_error`. Ktor's
`BadRequestException`, which `call.receive<T>()` throws when a body will not deserialise and a typed
`@Resource` parameter throws when it will not parse, is not a `KonektException`. So a caller who sent
the wrong shape was told the server broke. The completeness the sealed `when` guarantees is
completeness over **our** refusals, and it reads as completeness over all of them.

**`GET /api/v1/screens/history/page` answered 500 for every client that scrolled.** The two screen
routes beside it answer through `respondKompotComponent(json, …)`; the page route used a plain
`call.respond`, which serialises through ContentNegotiation's `Json` — the default one, carrying none
of this build's dictionary. `SerializationException: Serializer for subclass 'OrderRowComponent' is
not found in the polymorphic scope of 'KompotComponent'`.

`CallRespondUsageTest` existed to prevent exactly this and did not, for two reasons worth separating.
Its pattern named `Screen.build` and not `Screen.page`, because a page response is not a
`KompotComponent` — but its `items` are, so the rule was never about the root type. And its positive
half asked whether the FILE mentions `respondKompotComponent`; `PurchaseRouting.kt` does, for the two
routes above. It now asks per call site, over a window rather than a line so a broken argument list
is not a false positive. Both halves were proved by putting the defect back.

## What the walk still cannot reach, and it is not a formality

Five of the eleven checks have no target, and the two that matter are named here rather than left in
the declarations file:

- **`updates`** — the check validates a RECORDING of an event stream and never opens a connection.
  Nothing records one. The live channel is the load-bearing endpoint of this server and is held to
  nothing at all. The capture is now the only missing piece, and it is a separate item.
- **`idempotency`** — konekt implements no `Idempotency-Key` contract, so there is nothing to
  exercise. `allowStateChangingChecks` is off and the check returns before its counter exists, which
  is why an ABSENT counter is treated apart from a zero.

## The kit assumes the way in is a kompot form

`TckRunner.authenticate` posts a fixed `{formId, fieldId, values}` envelope to `TckConfig.loginPath`
and offers no way to hand it a token. konekt's login is not a form — `kompot-auth` is one
`update_session` action, so the OTP exchange is this product's own (research-architecture §1.5) — and
`POST /api/v1/auth/otp/verify` takes a plain `VerifyOtpRequest`.

The envelope is therefore unwrapped in a `TckTransport` decorator, which is the seam the kit itself
names as the only thing its checks know about transport. What that decorator deliberately does NOT do
is add a header: `securedEndpointsRejectAnonymous` asks a secured endpoint for a 401 with no token,
and a transport quietly carrying one would turn that check green while proving the opposite. Filed as
an upstream proposal — see [research-upstream-proposals](../research/research-upstream-proposals.md).
