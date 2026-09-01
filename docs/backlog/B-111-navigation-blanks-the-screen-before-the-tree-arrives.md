---
id: B-111
title: "Every navigation blanks the whole window until the next tree arrives"
status: done
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

## What was decided, and why the middle answer is the trap

**The previous tree, with its interactivity removed.**

- A **spinner over nothing** is the blank window this replaces, with a mark on it.
- A **skeleton** needs the shape of a screen the server has not sent, which is a client with an
  opinion about a tree it has not seen — the one thing this architecture is for not having.
- The **previous tree** is the least jarring and the most misleading: a screen that looks live, sits
  under the address of a different one, and still takes presses. A press on it starts an errand from a
  screen the subscriber has already left.

So: kept, dimmed to 0.45, and deaf — the pointer barrier consumes on the **initial** pass, because on
any later pass a button underneath has already been offered the event. The bottom bar is deliberately
**outside** that barrier: switching tabs again is the one thing that must keep working while a tab is
loading.

## The half the item was filed without knowing

`screens.fetch` was called inside a `LaunchedEffect` with nothing catching it. A fetch that threw
killed the effect and left the composition **permanently blank, with no way to ask again** — and
because the blank was the same blank, a failed screen and a slow one were literally indistinguishable.
It is caught now, says so in words a subscriber can act on, and offers a retry. No exception text: what
went wrong is a socket, a status code or a parse, and none of the three is a sentence for a
subscriber.

## What keeping the screen broke, which is the interesting part

`:client:standTest` failed — deterministically, three times out of three — on the login flow, and the
mechanism is one this change made visible rather than created:

`KonektFormScreen` remembers its `FormController` by **form id**. While the frame nulled the screen
between fetches, the whole subtree was unmounted and a fresh controller was built every time. Nothing
unmounts it now — so a login refused for a wrong code came back with **the wrong code still in the
field**, and the test's next `performTextInput` appended the real one to it.

The rule that settles it, and it is the one a subscriber would expect: **a form is rebuilt when the
server's answer changes.** `KonektScreenSource` keys the form on the response. An identical answer
refetched keeps what was typed — that is the same screen — and an answer carrying a refusal is a
different one, so it clears.

That was caught by accident: the stand test types into the same field twice, and appending happened to
break it three steps later. `LoginStandTest` now **states** it, so the next reader knows the double
typing is load-bearing.

## Proved by mutation

| Mutation | Result |
|---|---|
| `remember(current)` — the defect verbatim | all three transition assertions FAILED, including the bar |
| the barrier never engages | `the screen being replaced stops taking presses` FAILED |
| the barrier never lifts | that test **and** the transition test FAILED |
| the failure state is never shown | `a fetch that throws says so and can be retried` FAILED |
| the form is not keyed on the answer | `LoginStandTest` FAILED |

## Acceptance criteria

- AC: the furniture that is common to two destinations does not disappear between them. A tab press
  keeps the bar on screen. **Asserted directly** — `a tab press keeps the bar on screen while the next
  tab loads`, with a fixture whose tree actually carries a bar, because a fixture without one cannot
  say anything about the thing that was reported.
- AC: what fills the content area during the fetch is a decision that is written down. **Written in
  `KonektApp.kt` where it is made**, not only here.
- AC: a failed fetch is distinguishable from a slow one. **It was not even catchable before** — see
  above.
- AC: it is exercised by something that can actually see it. **`NavigationKeepsTheScreenTest` holds
  the second response open with a `CompletableDeferred` and asks what is drawn while it is held** — a
  gate rather than a delay, because a test that waits a fixed time asserts about a race it does not
  control.
- AC: if part of the answer belongs in kompot, the issue is filed and cited. **Not filed, and the
  reason is what this turned out to be:** none of it was kompot's. The blanking was one `remember`
  key here, the uncaught throw was this file's, and the form's controller lifetime is
  `KonektFormScreen`'s — konekt's own, all three. A host that owned screen transitions is still a
  reasonable thing for the toolkit to offer, and it is a proposal to make from experience rather than
  from one application's bug.

## Anchors

| What | Where |
|---|---|
| The line that blanks it | `client/src/commonMain/kotlin/io/konekt/client/app/KonektApp.kt` |
| Why the bar travels with the tree | `B-51`, and `Shell.bottomNav` in `server/src/main/kotlin/io/konekt/screens/Shell.kt` |
| Where a screen is fetched | `client/src/commonMain/kotlin/io/konekt/client/app/KonektScreenSource.kt` |
