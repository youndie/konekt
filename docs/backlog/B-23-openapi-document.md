---
id: B-23
title: "Publish an OpenAPI document, because the conformance kit reads one"
status: open
priority: P1
size: S
stage: stage-m4-proof
blocked_by: [B-07]
---

# B-23 — Publish an OpenAPI document, because the conformance kit reads one

`kompot-tck` points at a running server and reads endpoint kinds out of the deployment's OpenAPI
document; it assumes no addresses, which is what lets it run against an implementation on any stack
(research §1.10). Without the document there is no walk, so the document is a build artefact rather
than documentation.

- **The decision and its reason.** Generated from the routing rather than hand-written, so it cannot
  fall behind the routes the way a hand-written one does within a sprint. It is committed, and a diff
  in it during CI is a review signal.
- The rejected alternative is hand-maintaining it. The TCK would then be walking the document's idea
  of the server.
- Not covered: publishing it to consumers. It exists for the walk and for review.

- AC: `./gradlew :server:openApi` writes a document naming every kompot endpoint kind the server
  serves, and the committed copy matches.
- Anchors: `server/src/main/kotlin/io/konekt/openapi/`, `docs/api/openapi.json`.

Background: [research-architecture](../research/research-architecture.md) §1.10.
