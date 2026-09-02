---
id: B-113
title: "An SSE update replaced a row inside the allowance card with a card of its own"
status: done
priority: P1
size: S
stage: stage-m7-completeness
---

# B-113 — The screen was right until it changed

Reported from the running desktop client against the deployment: *the table on the home screen looks
right, but the SSE updates break it.*

## What it was

`B-105` grouped the three counters into one **Your allowance** card, so a counter there is a **row** —
no ground of its own, no corners, no inset. The shape was a parameter with a default:

```kotlin
fun of(counter: UsageCounter, at: Instant, inline: Boolean = false)
```

`HomeScreen` passed `inline = true`. The live-update path did not:

```kotlin
push.push(subscriberId, UsageCounterCards.idOf(updated), cards.of(updated, clock.now()))
```

So the instant a counter moved — every few seconds, with the traffic simulator running — SSE replaced
a row with a **full card**: its own background, its own corners, its own inset, nested inside the card
it was living in. And since `B-112` gave a standalone counter card the CARD tier, that replacement is
a 36-radius block inside a 36-radius block.

**Correct until it changed**, which is the worst kind of correct: every screenshot, every tree
assertion and every scenario builds the screen and looks at it. None of them looks at it *after* an
update arrives.

## The default is what made it possible

A caller that forgot the argument got the shape **nothing uses**. Both production callers wanted the
same answer and only one of them said so.

So the parameter is gone: a counter from `UsageCounterCards` is always a row. The travel screen asks
`RoamingPackageCards`, a different factory with a different answer, so nothing here needed the
flexibility — it existed only so one caller could be wrong.

## The rule underneath it

**Whatever builds a node for a screen is the only thing that builds its replacement.** A push that
differs from the screen in ANY field is a screen that rearranges itself while somebody is looking at
it, and the next thing added to a card will be a parameter too.

`LiveUpdateSendsTheSameNodeTest` states it: for every counter kind and state, the node the update
sends must equal the node the screen drew, compared by value. It also asserts the travel card's
factory has not grown an argument the two paths could pass differently.

## Proved by mutation

| Mutation | Result |
|---|---|
| control | green |
| the flag comes back and the push takes the default | `the card an update pushes is the card the screen drew` FAILED |

## Why nothing caught it

`TrafficChainTest` asserts that a push happens, that it names the right node and that the copy changes
with the state. It never compared the pushed node with the drawn one — and the goldens cannot, because
a recording is a screenshot of a screen at rest.

The neighbouring rule in `CLAUDE.md` — *a live update names the node it replaces* — was about the ID.
This is the other half: naming the right node and sending the wrong shape lands in exactly the same
place.

## Anchors

| What | Where |
|---|---|
| The factory | `feature/usage-server-data/.../UsageCounterCards.kt` |
| The two callers | `server/src/main/kotlin/io/konekt/screens/HomeScreen.kt`, `server/src/main/kotlin/io/konekt/mocks/traffic/UsageConsumer.kt` |
| The guard | `server/src/test/kotlin/io/konekt/screens/LiveUpdateSendsTheSameNodeTest.kt` |
| What grouped them | [B-105](B-105-the-home-screen-diverges-from-the-canvas.md) |
| What made the replacement louder | [B-112](B-112-the-cards-do-not-use-the-canvas-geometry.md) |
