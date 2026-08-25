---
id: B-35
title: "An end-to-end stand on docker-compose, driven by one command in both places"
status: open
priority: P0
size: M
stage: stage-m2-live
blocked_by: [B-14, B-15]
---

# B-35 — An end-to-end stand on docker-compose, driven by one command in both places

The scenario this build exists to show crosses five processes: a kompot form, a petich saga, the
outbox, the booblik broker, and back to an open SSE stream. Every test below this level can pass
while that chain is broken at a seam, because each of them owns one end of it. The stand is the only
thing that owns the whole.

- **The decision and its reason.** `deploy/compose.yaml` brings up Postgres, the broker, the server
  and the three observability binaries, and the suite drives it over HTTP with the same command
  locally and in CI. A stand only CI knows how to start is a stand nobody debugs, and the failures
  worth catching here are the ones that only appear between processes.
- **`depends_on` uses `condition: service_healthy`, and the healthcheck asks the process a question.**
  A TCP check passes against a hung process — the kernel accepts into the backlog with no help from
  it — which turns the stand's own startup into a source of false green.
- Topics are declared to the broker at startup (`BOOBLIK_TOPICS: orders:1,usage:1,notifications:1`),
  because booblik fixes its topic set then and never again. Host ports are overridable through
  environment variables; 8080 is the most contested port there is.
- The rejected alternative is an in-process end-to-end test with the broker embedded. It is faster
  and it stops proving the thing the stand exists for, since packaging and networking are two of the
  seams.
- **The suite stays small on purpose**: the happy path, the compensated path, and one live update.
  End-to-end is the slowest and most fragile layer of any suite, and a large one gets muted.
- Not covered: the mobile clients. The stand drives HTTP; the client is covered by screenshots
  (`B-28`) and by the client conformance corpus.

- AC: `docker compose up` plus one Gradle task runs the three scenarios green from a clean checkout.
- AC: killing the broker mid-scenario fails the run with a message naming the broker, not a timeout.
- AC: the same command runs in CI, and the CI job declares its Docker requirement.
- Anchors: `deploy/compose.yaml`, `e2e/src/test/kotlin/io/konekt/e2e/`.

Background: [research-stack](../research/research-stack.md) D21,
[research-architecture](../research/research-architecture.md) §1.8.
