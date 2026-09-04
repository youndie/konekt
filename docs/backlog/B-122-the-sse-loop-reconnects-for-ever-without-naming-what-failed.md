---
id: B-122
title: "The realtime loop swallows every failure and reconnects for ever, so an auth expiry and a closed laptop are the same silence"
status: done
priority: P3
size: S
stage: stage-m7-completeness
---

# B-122 — The reconnect that never says why

`SseRealtimeSource` runs `while (isActive)`, opens the stream, and on anything that is not a
cancellation does this:

```kotlin
} catch (failure: Exception) {
    // Everything else is the network: a closed laptop, a proxy timing out, a server
    // rolling. Reconnecting is the whole point of this class.
}

delay(backoff.after(attempt))
```

The comment is right about what it wants and wrong about what it gets. A closed laptop and a proxy
timing out do belong here — and so do an expired token, a 400 the server will answer identically
for ever, and a serialisation error in a frame this client cannot read. Those three reconnect on the
same backoff, for as long as the screen is open, and **`failure` is never read**, so nothing in any
log names them. The subscriber sees a card that stops updating; the operator sees a client that
reconnects politely.

**Found by a tool, not by reading.** `renovate/sborka` (PR #7) fails CI on
`:client:ktlintCommonMainSourceSetCheck`, because sborka `0.2.0` ships **kapkan**, a rule set of
three, and this is `swallowed-failure`:

```
SseRealtimeSource.kt:96:19 this catches every Exception and never looks at failure —
the failure is gone, and the log that would have named it does not exist
```

Reproduced locally on 2026-09-04 by taking `sborka 0.2.0.28` in a worktree; the catalogue already
pins the same ktlint (`1.8.0`) and plugin (`14.2.0`), so the rule is the whole of what the bump
brings here. It is the only violation in the module.

## What it takes

One line that reads `failure` — through `KonektClientObservability`, which is where this build's
client-side degradations already go, so a reconnect storm becomes findable in tracy beside the
degradations rather than nowhere. Whether some failures should stop the loop rather than be logged
is a second question and a bigger one; this item is the log.

## Acceptance criteria

- AC: a failure that ends a stream is named once per attempt, wherever this build sends client
  observability, with the attempt number. **Held by the breadcrumb**, which carries the exception's
  simple name, its message, the attempt and whether the stream had ever connected.
- AC: `:client:ktlintCommonMainSourceSetCheck` passes under sborka `0.2.0.28`, which unblocks PR #7.
  **Met**, and it was the only violation in the module.
- AC: the comment says which failures this loop expects to retry for ever and which it does not,
  rather than calling all of them "the network". **Met as far as naming goes**; deciding which ones
  should end the loop is left open on purpose and is not this item.

## What was done

**A breadcrumb, once per attempt, naming the exception and the attempt number.** Not a tracy log:
`SseRealtimeSource` is constructed in ten places, nine of them tests, and taking an agent in its
constructor to write one line would have been machinery. `Katcher.addBreadcrumb` needs nothing this
class did not already have, works on every platform, and — the reason `KonektClientObservability`
puts the breadcrumb before its own log — it is an in-memory append that attaches to the NEXT crash.
A stream that reconnected forty times before something else fell over is exactly the context a crash
report cannot reconstruct afterwards.

The comment beside it now says what the old one did not: that an expired token, a permanent 400 and a
TLS failure land here too and retry on the same backoff, and that whether some of them should stop
the loop is a larger question than this line.

**Verified both ways, on the rule that found it.** With `sborka 0.2.0.28` in a worktree,
`:client:ktlintCommonMainSourceSetCheck` failed at `SseRealtimeSource.kt:96` before the change and
passes after it — the only violation in the module, so **PR #7 is unblocked by this alone**. The
client's own suite still runs: every `@Test` in 29 classes.

## Anchors

| What | Where |
|---|---|
| The loop | `client/src/commonMain/kotlin/io/konekt/client/realtime/SseRealtimeSource.kt` |
| Where a client degradation goes today | `client/src/commonMain/kotlin/io/konekt/client/observability/KonektClientObservability.kt` |
| The rule that found it | sborka `0.2.0`, kapkan `swallowed-failure` |
| The blocked bump | [konekt#7](https://github.com/youndie/konekt/pull/7) |
