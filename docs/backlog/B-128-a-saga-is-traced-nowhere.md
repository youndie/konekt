---
id: B-128
title: "What one saga did is written nowhere this server can read it"
status: done
priority: P2
size: S
stage: stage-m7-completeness
---

# B-128 — the saga trace, into the log

Every signal this server has about a saga is keyed by saga type or by request, and the row keeps
the latest position only. "What did top-up X go through" is answered by reading code.

petich now has a per-saga event hook (its B-58, `PetichTracer`) and a sink that writes one line per
event (its B-60, `LinePetichTracer`). petich's B-60 asks whether **two weeks of reading these
lines** answers anything a counting test double had not, and konekt and shashki are the two places
that can say. This item is the wiring; the two weeks are petich's to count.

- **One line per event through slf4j**, logger `io.konekt.saga`, prefix `petich.trace` — the same
  format shashki logs, pinned by a test in petich.
- **The replica is the instance name kore's observability already carries**
  (`config.observability.instance`, `HOSTNAME` by default), so a trace line and a span name the same
  pod.
- **The bump crosses petich B-49..B-65**, from `0.4.0.88` to `0.4.0.112`, and B-54 adds two columns
  to the saga table: `V14__petich_0_4_0_112.sql`.
- Not covered: `ClaimedSweep`, `saga_sweep_claim` and V12, which petich B-26 made redundant
  (youndie/konekt#48). Still wired; still this repository's decision.

## Acceptance

- `petich = "0.4.0.112"`, V14 adds both columns, and `./gradlew build` is green.
- The server's own log carries `petich.trace` lines when a saga runs.

## Findings

**No source change beyond the wiring**, across seventeen petich items. `./gradlew build` on the
Linux box against a real Postgres: green, 293 tests in the result files written by that run, no
failure.

**The migration is held by guards that already existed.** `KonektSchemaTest` with V14 moved aside
fails naming exactly the two columns — `ADD compensating_from_index INT NULL`, `ADD
compensating_towards VARCHAR(32) NULL` — which is the positive control for the file. And the
migration lock file refused V14 until its checksum was recorded, as it is meant to.

**Behaviour this server will see.** petich B-65: a saga read mid-pass says `PROCESSING` where it said
`DRAFT`, or `PENDING_SIGNATURE` after a resume. The tariff route refuses a resume unless the row is
`PENDING_SIGNATURE` (`TariffUseCases.kt`), so a second resume arriving while the first is carrying the
saga on is refused rather than raced — the case that guard reads as written for.
