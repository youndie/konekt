---
id: B-26
title: "metrik, tracy and katcher wired, and a compose file that runs all three"
status: open
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
