---
id: B-24
title: "The TCK gate asserts what it visited, not that it was clean"
status: open
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
- Anchors: `server/src/test/kotlin/io/konekt/conformance/TckGate.kt`, `.github/workflows/ci.yaml`.

Background: [research-architecture](../research/research-architecture.md) §1.10, Risk 2.
