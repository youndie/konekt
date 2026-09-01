---
id: B-107
title: "The consumer never recovered from a broker restart, and the offset hypothesis was wrong"
status: done
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

## The hypothesis was wrong

The item was filed guessing that the consumer held an offset past the new end and that a fetch past
the end is answered by closing the socket. **Both halves are false, and the source says so:**

- `Session.fetch` answers an out-of-range offset with `ErrorCode.OFFSET_OUT_OF_RANGE`, cleanly. It
  does not close the connection.
- `BooblikConnection` opens **one** `SocketChannel` in its constructor and never dials again. Held
  for the life of a process — which is exactly what `BrokerConnection` did — a replaced broker pod
  leaves every user of it holding a dead socket for ever.

That accounts for every observation, including the one the offset story could not: the broker
reporting `conns 0`. Nothing was refusing our fetches. **Nothing was dialling it.**

It also means the outbox relay had the same defect, silently: `BooblikOutboxPublisher` was handed a
`Producer` once at startup, so after a broker restart every pending row was retried against a
connection that would never answer. An outbox that never drains and an outbox with nothing in it
look identical from outside.

## What was done

| | |
|---|---|
| `BrokerConnection` | holds a **generation**, and `reconnect(seen)` replaces the socket unless somebody already has. Two callers finding the same dead socket ask to replace the same generation; the second finds the work done rather than discarding the first one's fresh connection |
| `UsageConsumer` | recovers from a finished connection at **its own position** — a poll that failed consumed nothing — and separately from `OFFSET_OUT_OF_RANGE` by seeking to the end and **saying how many events it skipped**. The second path is what `B-100` made reachable: retention now deletes segments, so a slow consumer can be passed by it |
| `BooblikOutboxPublisher` | takes the holder, clears its `TopicHandle` cache on a generation change, and asks for a reconnect before rethrowing — the relay's own retry then runs on a live connection |

**Two types and not one.** `BrokerConnection.isFinished` matches `IOException` **and**
`ClosedSendChannelException`: the broker going away raises the first from the reader, closing from
this side raises the second from the outbound channel. The first version matched `IOException` alone
and would have worked in production and been exercised by nothing — the test is what said so.

## Proved by mutation

| Mutation | Result |
|---|---|
| control | 128 tests, green |
| the loop stops recognising a finished connection | `TrafficChainTest > a consumer whose connection dies…` FAILED |
| `isFinished` back to `IOException` alone | that test **and both** `BrokerReconnectTest` recovery tests FAILED |

`BrokerReconnectTest` covers the mechanism; `TrafficChainTest` covers **the running loop**, started
with `start()` and broken underneath, because every test of the mechanism would have stayed green
over a `UsageConsumer` whose recovery had been deleted.

## Two things this cost on the way

- **A `@Test` that returned a value never ran.** Written as `= runBlocking { … }` ending on
  `assertNotNull`, the method returned `Long`, JUnit skipped it silently, and every mutation written
  to prove it worked passed. It is `Unit`-terminated now, with a comment saying why.
- **Three tests found the end of a log with one poll**, the same mistake as
  [B-108](B-108-the-usage-consumer-starts-a-megabyte-from-the-beginning.md) — invisible until one
  test made a log longer than a single poll. They ask METADATA now.

## Acceptance criteria

- AC: the hypothesis is settled by reading what the consumer actually asks for, not by inference.
  **Settled, and refuted** — see above. What was asked for was fine; what it was asked over was dead.
- AC: `io.konekt.mocks.traffic.consumer` recovers on its own from a fetch it cannot satisfy — it
  already knows how to start from the end, and starting there again is the whole fix.
- AC: it does not recover **silently**. A consumer that reseeks has skipped records, and one log line
  saying so is the difference between a self-healing client and one that hides a gap.
- AC: a test drives it: a consumer holding an offset past the end of a log recovers within a bounded
  number of polls rather than looping.
- AC: an issue is filed against booblik for the truncation, with both startup lines quoted. Ask before
  filing anything against a repository that is not ours. **Filed as `youndie/booblik#25`**, with the
  mechanism read out of `LogSegment.open` rather than guessed: for a `MAPPED` segment the recovery
  limit is the CONFIGURED capacity rather than the file's size, so a segment written under a larger
  one is walked only as far as the new limit and `truncateTo` then plants a zero length prefix there.

## Anchors

| What | Where |
|---|---|
| The consumer that looped | `server/src/main/kotlin/io/konekt/mocks/traffic/` |
| The settings that shrank the segment | `charts/konekt/values.yaml` — `broker.segmentBytes` |
| Why they were introduced | [B-100](B-100-the-broker-keeps-every-event-for-ever.md) |
| The deploy that exposed it | [B-106](B-106-reuse-values-drops-what-the-chart-added.md) |
