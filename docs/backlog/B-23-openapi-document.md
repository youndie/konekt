---
id: B-23
title: "Publish an OpenAPI document, because the conformance kit reads one"
status: done
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
- Anchors: `server/src/main/kotlin/io/konekt/openapi/`, `docs/api/openapi.json`,
  `server/src/test/kotlin/io/konekt/openapi/`, `docs/api/api-openapi.md`.

## What was built, and the one thing that is not closed

The document is generated from the **routing tree**, not from the route classes and not by hand.
`RouteInventory` walks the tree Ktor built out of the `@Resource` classes, so the path, the method,
the query parameters and — the point of the exercise — **the auth tier** are derived rather than
declared. The tier reaches the tree through a route table in the composition root (`konektRoutes`,
`AuthTier`), which `Application.module` mounts and the generator mounts into an application with no
database. One table, read twice: a generator with a route list of its own would have been a second
copy of the contract.

What cannot be derived — the success status, the body, the kompot endpoint kind — is
`konektEndpointFacts`. The generator refuses to build unless the two halves name exactly the same
endpoints, which is what makes "lists a route that does not exist, or omits one that does"
impossible rather than unlikely. Both guards were proved to bite by breaking them; see
[api-openapi](../api/api-openapi.md).

**`:server:openApi` is the recorder and the only thing that writes the document.** It is a `Test`
task filtered to `OpenApiDocumentTest` with `KONEKT_OPENAPI_RECORD=true`, never up to date (its
output is a file outside the build directory, so an `UP-TO-DATE` here would read as success and mean
"not recorded"), and wrapped by `make openapi`, which adds the `LOCAL=1` this repository needs: a
recording written on the mutagen replica is reverted by the next sync and looks like it did nothing.
The comparison is an ordinary test and therefore runs on every build.

Proved in both directions rather than assumed: renaming one key in the committed file fails
`OpenApiDocumentTest > the generated document matches what is committed` at
`OpenApiDocumentTest.kt:67`, and `:server:openApi` then restores the file byte for byte
(sha256 `c1aa4542…` before the mutation and after the re-record).

Background: [research-architecture](../research/research-architecture.md) §1.10.
