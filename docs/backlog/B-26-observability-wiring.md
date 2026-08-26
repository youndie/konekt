---
id: B-26
title: "metrik, tracy and katcher wired, and a compose file that runs all three"
status: wip
priority: P1
size: M
stage: stage-m4-proof
blocked_by: [B-08]
---

# B-26 — metrik, tracy and katcher wired, and a compose file that runs all three

Three self-hosted binaries beside the server: metrik ingesting over UDP `:9999`, tracy over HTTP with
an ingest key, katcher taking server errors. The value being demonstrated is one purchase visible in
all three at once — in tracy by `orderId`, in metrik as latency on the route, in katcher if it fell
over.

- **The decision and its reason.** tracy's entity fields are indexed on `msisdn`, `iccid` and
  `orderId`, because "show me everything that happened to this order" is the question a demonstration
  of an incident is built around, and it is only answerable if those were indexed when written. tracy
  logging is `suspend` by design — it runs inside the request and the trace context lives in the
  coroutine — so it cannot be called from a non-suspending helper, which shapes where logging sits.
- The rejected alternative is one of the three plus stdout for the rest. It halves the setup and
  removes the only reason to have three.
- Not covered: alerting thresholds. metrik's alerts exist; tuning them needs traffic this build does
  not have.

- AC: one purchase produces a tracy trace reachable by `orderId`, a metrik data point on the route,
  and a katcher report if the route throws.
- AC: an agent switched off is visible as absent data, not as healthy silence — asserted by a check
  that fails when a service reports nothing after a run.
- AC, **carried from `B-05`**: konekt's `KompotDegradationSink` reaches tracy with `originalType` as
  an indexed field, and leaves a katcher breadcrumb. The mechanism and the reported-once property are
  done and tested; what is missing is where the record goes, which is this item's subject. Until it
  lands, an unknown component is drawn correctly and counted nowhere — which is the exact blindness
  kompot#81 was filed about.

- Anchors: `server/src/main/kotlin/io/konekt/observability/`, `deploy/compose.yaml`.

Background: [research-architecture](../research/research-architecture.md) §1.9.

## What landed

Three services in the stand and three agents in the server, with the trio deliberately having no
`depends_on` relationship with it in either direction: an agent that cannot reach its collector must
not stop the product from serving, and wiring the dependency would hide exactly that — the stand would
refuse to start instead of showing what a running system does when its observability is down.

| | Wired as | Measured on the stand |
|---|---|---|
| metrik | Ktor plugin, UDP to `metrik:9999` | `konekt-server` at 0.27 rps, p95 27.95 ms |
| tracy | agent + delivery + `install(Tracy)` | 4 stored records, `entityRefs: {orderId: 4, subscriberId: 4}` |
| katcher | `Katcher.start` — the same object the iOS client uses | ingest answers; nothing has thrown yet |

**Each agent is all-or-nothing, and a half-configured one refuses at startup.** All three answer a
missing endpoint or key by doing nothing — metrik has an `enabled` flag, tracy's delivery never
connects, katcher's `start` prints a line and returns. That is three ways to arrive at one failure: a
deployment that meant to be observed and is silent. `<NAME>_ENDPOINT` without `<NAME>_KEY` is an error
where it is configured; both absent is a decision.

**The order id is indexed at the point it is written**, which is the only place it can be. tracy turns
an indexed field into an entity key, so "show me everything that happened to this order" is answerable
— and the same log line without the flag produces a record tracy stores and nobody can find.

## AC 2, and it is the one that made the rest non-vacuous

`ObservabilityScenarioTest` drives a real purchase and then asks each collector whether anything
arrived, failing on zero. Not on presence: a service row can exist from a handshake, so the assertion
is on a non-zero request rate and on a non-zero count of `orderId` entity refs.

**Proved to bite, and the first two attempts did not.** Restarting the collectors was not enough — the
test creates its own traffic, so fresh data arrives. Emptying them was not enough either, because
`docker compose rm -v` removes anonymous volumes and these are named. With the agents switched off AND
the named volumes gone, it fails by name:

```
java.lang.AssertionError: waited 20s for: metrik to have seen konekt-server
```

That is why the compose file lets both variables of a pair be overridden to empty: running the stand
with an agent off is the only way to check that this assertion can fail at all.

## The check was measured where it could not fail, and CI said so

It passed locally and failed in CI on the next push, and the timeout was not the reason.

**The metrik agent's aggregation window defaults to sixty seconds** — read in
`metrik/shared/.../Protocol.kt` rather than recalled. The agent buffers a window and sends it when the
window closes, so a process that has just started reports nothing at all for a minute. Every local run
was against a stand that had been up for a while, where a window had long since closed; CI starts a
fresh one and the whole e2e run is shorter than a single window.

The fix is the window rather than the timeout: `METRIK_WINDOW_MS` is five seconds on the stand, which
is a stand-specific setting with the same justification as tracy's `sampleRate = 1.0` beside it. Then
verified the way it should have been the first time — `docker compose down -v`, a cold stand, the
whole suite green in fifteen seconds, which a sixty-second window could not have produced.

That the local port had to move to do it is the compose file's own design paying off: another
container held 55432, and every port in the stand is overridable precisely so the stand is not the
thing that refuses to start.

## `wip`, and what is left

- **AC 1's katcher half.** "a katcher report if the route throws" is unproved: nothing in this stand
  throws on purpose, so what is checked is that the ingest address answers rather than 404s. The
  missing piece is a route that fails on demand, in the shape of `PAYMENT_MOCK_MODE`.
- **AC 3, carried from `B-05`.** `KompotDegradationSink` still reaches nothing: an unknown component
  is drawn correctly and counted nowhere, which is the exact blindness kompot#81 was filed about. The
  handle it needs now exists (`KonektTrace`), so this is a sink implementation rather than plumbing.
- **The read path trusts `X-Auth-Request-User`.** metrik and tracy sit behind a reverse proxy in a
  real deployment; the stand has none, so the header is simply believed. Fine for reading a stand and
  not fine for anything else — and worth stating because the same header on an INGEST route would be
  a gift to anyone who can reach it.
