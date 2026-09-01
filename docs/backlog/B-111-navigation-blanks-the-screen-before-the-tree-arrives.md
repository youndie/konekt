---
id: B-111
title: "Every navigation blanks the whole window until the next tree arrives"
status: open
priority: P1
size: M
stage: stage-m7-completeness
---

# B-111 — The screen empties, the bar disappears, and then the application comes back

Reported from the running desktop client: switching tabs — and navigating anywhere at all — shows an
empty window first and the screen a moment later.

## It is ours, not kompot's

The suspicion when this was reported was that it belonged upstream. It does not. One line in
`client/src/commonMain/kotlin/io/konekt/client/app/KonektApp.kt` accounts for the whole of it:

```kotlin
var screen by remember(current) { mutableStateOf<Screen?>(null) }
```

`current` is the destination. The instant it changes, this state is **reset to null** — before any
request is made — and the content box then has nothing to draw until the fetch answers. Over a local
stand that gap is milliseconds and easy to miss; over a deployment it is a round trip, and a round
trip is exactly how long the window is empty.

**And the bar goes with it**, which is why it reads as the application vanishing rather than as a
screen loading:

```kotlin
val shell = remember(screen) { (screen as? Screen.Tree)?.component?.withoutShell() }
```

The bottom bar is part of the tree the server sends, by design (`B-51`) — so with no tree there is no
bar, no title, no frame. Every piece of furniture disappears at once and comes back together.

## Why this is a P1 in a reference implementation

This build exists to show that backend-driven UI is a reasonable way to write an application. A blank
window on every tab press is the single most visible argument that it is not, and it is the first
thing anybody evaluating the idea will see. The defect is small; what it costs is the point of the
repository.

## What it is not

Not the network being slow — the round trip is real and is not going away, and a build that only
looks right on a fast connection has not solved this. The question is what is on screen **during** a
round trip that will always exist.

## Acceptance criteria

- AC: the furniture that is common to two destinations does not disappear between them. A tab press
  keeps the bar on screen.
- AC: what fills the content area during the fetch is a decision that is written down — the previous
  tree, a skeleton, or a spinner — with the reason. Keeping the previous tree is the least jarring and
  the most misleading: a stale screen that still takes presses is worse than an empty one.
- AC: a failed fetch is distinguishable from a slow one. Whatever is drawn while waiting must not be
  what is left behind when the request never answers.
- AC: it is exercised by something that can actually see it. A tree assertion cannot: the tree is
  correct both before and after, and the defect lives entirely in between. A test that holds the
  response open and asserts what is on screen meanwhile is the shape this needs.
- AC: if part of the answer belongs in kompot — a host that owns the transition rather than each
  application inventing one — the issue is filed against it and this item cites it. That is worth
  asking even if konekt fixes its own half first.

## Anchors

| What | Where |
|---|---|
| The line that blanks it | `client/src/commonMain/kotlin/io/konekt/client/app/KonektApp.kt` |
| Why the bar travels with the tree | `B-51`, and `Shell.bottomNav` in `server/src/main/kotlin/io/konekt/screens/Shell.kt` |
| Where a screen is fetched | `client/src/commonMain/kotlin/io/konekt/client/app/KonektScreenSource.kt` |
