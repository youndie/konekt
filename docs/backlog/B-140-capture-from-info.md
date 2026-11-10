---
id: B-140
title: "At kore's WARN floor a healthy server still sends tracy no records"
status: done
priority: P1
size: S
stage: stage-m4-proof
blocked_by: [B-139]
---

# B-140 — at kore's WARN floor a healthy server still sends tracy no records

B-139 took kore 0.1.17, whose wiring attaches tracy's log capture from `WARN`. `v0.1.46` went to the
stand on 10.11.2026 (helm revision 53), and half an hour later tracy still had `storedRecords = 0`
for `konekt-server`: every `warn`/`error` this server writes is a failure — the broker reconnecting,
the election losing Postgres, the shared bus failing — and a healthy stand has none.

## Decision

`tracyCaptureFrom = Level.INFO` in `configureObservability`, by the owner's choice. kore's `WARN` is
the right default for a service at production rates, because a captured line carries no trace and
tracy sends every one; this is a reference build at stand traffic, where the volume is small and an
empty record stream is the defect, not the economy.

## Acceptance

- [x] the wiring passes `INFO`; the server compiles and CI is green
What only the stand can show — `konekt-server` with `storedRecords > 0` and its templates in tracy's
`top_templates` once the release is rolled out — is youndie/tracy M-150's acceptance, read from
tracy rather than from this repository.
