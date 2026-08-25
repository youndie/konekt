---
id: B-24
title: "The TCK gate asserts what it visited, not that it was clean"
status: wip
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

## What landed, and the half that did not

**`wip` and not `done`, because the walk does not happen yet.** Both acceptance criteria above are
observably met and the gate was proved to bite — see below — but the item's own decision paragraph
says *the CI step parses the per-check target counts of a run*, and there is no run. Running
`kompot-tck` needs `testImplementation(libs.kompot.tck)` in `server/build.gradle.kts`, and that file
was outside the lane that did this work. Everything else was possible without it, so it was done.

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
