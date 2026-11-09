---
id: B-139
title: "The server sends tracy spans and not one log record"
status: done
priority: P1
size: S
stage: stage-m4-proof
blocked_by: []
---

# B-139 — the server sends tracy spans and not one log record

Found from tracy's side on 09.11.2026 (youndie/tracy M-150): `konekt-server` on the stand had
`storedRecords = 0` next to 467 MB of `producedBytes`, and an empty `top_templates` over everything
tracy still keeps (from 11.10), while `search_spans` returned its fresh spans. The span path worked;
no log line ever left the process.

The mechanism, read in the code: `configureObservability` hands everything to kore's
`installKoreObservability`, and kore 0.1.4 installs tracy's agent, plugin and delivery and attaches
**no capture** — the plugin writes the span of each request and nothing else. The server logs
through SLF4J (`logback.xml`, STDOUT only), and SLF4J reaches tracy only through tracy's
`TracyLogbackAppender`, which nothing attached. The one direct `agent.logger` call is "purchase
started" (`PurchaseRouting.kt`), and the stand makes no purchases.

## Decision

Fixed in kore, not here (youndie/kore#120, kore B-71): `installKoreObservability` attaches the
capture from `WARN` by default, so every kore consumer gets it. This item moves konekt to the release
that carries it, **0.1.17**, and pays for the jump from 0.1.4:

- `server.startForKore()` instead of `start(wait = false)` — on the JVM `start()` adds Ktor's own
  shutdown hook, which stops the engine mid-announce, and `EngineDrain` refuses to be built beside it
  since kore B-60;
- `EngineDrain(..., DrainGate())` — the latch kore B-61 gives the drain; konekt installs no shutdown
  refusal, so nothing reads it.

`WARN`, not `INFO`, is kore's floor and the right one here: a captured line carries no trace, so
tracy sends every one, and `INFO` would be a line per request.

## Acceptance

- [x] kore 0.1.17 in the catalogue; the server compiles on it and CI (`check`, `build` with the e2e stand) is green
- [x] `startForKore()` and the drain's latch, as above

The mechanism itself is kore's to prove and is: `LogCaptureTest` in kore, through
`installKoreObservability`, on jvm and linuxX64. What only the stand can show — `storedRecords > 0`
for `konekt-server` and its templates in `top_templates` — is youndie/tracy M-150's acceptance, and
it waits for the next release of this server to be rolled out.
