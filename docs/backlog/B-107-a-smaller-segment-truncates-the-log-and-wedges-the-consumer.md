---
id: B-107
title: "Lowering the segment size truncated the log, and the consumer polled a vanished offset for ever"
status: open
priority: P1
size: M
stage: stage-m7-completeness
---

# B-107 — 108 MiB left the log at startup and nothing recovered from it

Observed on the test contour while deploying `v0.1.26`. Two facts, both read out of logs:

**Before** the broker restarted with `segment.capacity.bytes=33554432`:

```
usage-0: offsets 0..1582504, 1 segment(s), 141951836 bytes
```

**After**:

```
segment: mode=MAPPED capacity=33554432 index.interval=4096
usage-0: offsets 0..374473, 1 segment(s), 33554356 bytes
```

The existing segment had been created under booblik's `DEFAULT_CAPACITY` of 512 MiB and held 141 MiB.
Reopened under a 32 MiB capacity it kept exactly 32 MiB. **1.2 million records and 108 MiB went, and
the only thing that said so is the difference between two startup lines nobody diffs.**

## The half that hurt

The server was not restarted with the broker, and from that moment:

```
22:22:28.569 WARN io.konekt.mocks.traffic.consumer - a usage poll failed
java.io.EOFException: broker closed the connection
```

**Five times a second, indefinitely.** The broker's own metrics line read `conns 0 errors 0` — from
its side nothing was happening at all. Live usage counters were dead for the eight minutes it took to
notice: no update reached a screen, and neither side reported an error anybody was watching.

`kubectl rollout restart deploy/konekt` cleared it in one go.

## What is established and what is not

| | |
|---|---|
| **Established** | the log shrank to exactly the new capacity across a restart |
| **Established** | an unrestarted consumer then polled and got EOF at 5/s, indefinitely, with the broker counting no connection |
| **Established** | restarting the consumer fixed it completely |
| **Hypothesis** | the consumer held an offset near `1582504`, past the new end at `374473`, and a fetch past the end is answered by closing the socket |

The hypothesis is the obvious one and it is **not proved** — nothing here read the consumer's offset.
Proving it is the first task, not the fix: a symptom that reproduces is not a mechanism
([the rule this repository keeps paying for](B-97-the-rolling-check-can-run-a-stale-binary.md)).

## Why it matters beyond one deploy

Both halves are shapes, not accidents:

- **A config change that silently destroys data.** Lowering the segment size is a plausible thing for
  an operator to do — this deployment did it to make retention mean anything at all (`B-100`) — and
  nothing warned, refused, or migrated.
- **A client with no way back.** Whatever the broker's answer to an out-of-range fetch is, the
  consumer's response to it is to try the same thing again 200 ms later, for ever. A consumer that
  starts from the END of the log on boot has an obvious recovery available and does not take it.

The second is konekt's own code and fixable here. The first is booblik's and belongs upstream — this
is the second time this build has found a way to stop that broker with its health check still green
(`youndie/booblik#15` was the first).

## Acceptance criteria

- AC: the hypothesis is settled by reading what the consumer actually asks for, not by inference.
- AC: `io.konekt.mocks.traffic.consumer` recovers on its own from a fetch it cannot satisfy — it
  already knows how to start from the end, and starting there again is the whole fix.
- AC: it does not recover **silently**. A consumer that reseeks has skipped records, and one log line
  saying so is the difference between a self-healing client and one that hides a gap.
- AC: a test drives it: a consumer holding an offset past the end of a log recovers within a bounded
  number of polls rather than looping.
- AC: an issue is filed against booblik for the truncation, with both startup lines quoted. Ask before
  filing anything against a repository that is not ours.

## Anchors

| What | Where |
|---|---|
| The consumer that looped | `server/src/main/kotlin/io/konekt/mocks/traffic/` |
| The settings that shrank the segment | `charts/konekt/values.yaml` — `broker.segmentBytes` |
| Why they were introduced | [B-100](B-100-the-broker-keeps-every-event-for-ever.md) |
| The deploy that exposed it | [B-106](B-106-reuse-values-drops-what-the-chart-added.md) |
