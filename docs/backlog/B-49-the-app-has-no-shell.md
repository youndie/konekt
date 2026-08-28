---
id: B-49
title: "Four screens and no way between them except a banner"
status: done
priority: P1
size: L
stage: stage-m3-product
epic: feature-client-shell
---

# B-49 — Four screens and no way between them except a banner

The canvas draws a bottom bar on every frame of every section — `Home · Plans · Orders · Profile` —
and this build has none. What it has instead is one banner on the home screen offering "See plans",
which is the only transition a subscriber can make without typing an address. Orders exists and
cannot be reached. Profile does not exist at all.

The home screen is the same story told shorter. Section 01 of the canvas fixes a header, the number
the account belongs to, `Top up` and `History` beside the balance, the plan name with its renewal
date, three counters, and `Buy a package`. The screen serves a label, an amount, and whichever
counters exist — and only one kind of counter is ever granted, so the two states the canvas exists
to show, *Running low* and *Used up*, cannot appear beside a normal one on any real account.

- **The decision and its reason.** The tab set is SERVER-DRIVEN, as a component of konekt's own
  dictionary, because a white-label product changing its tabs per brand without a client release is
  the same claim `B-22` makes about colour — and a bar compiled into the client would be the one
  part of this product that contradicts it.
- **And the graph comes from `kompot-navigation`,** which this build carries and has never used.
  Today `Main.kt` holds a hand-written `Map<deeplink, address>`; four tabs plus a back stack is
  exactly where that stops being a map and starts being a graph somebody has to keep in step with
  the server. It also closes the one entry in `KONEKT_CHECKS_WITH_NOTHING_TO_VISIT` that names a
  toolkit module rather than a missing surface: the conformance walk has never had an endpoint of
  kind `graph` to visit.
- The rejected alternative is a client-side tab list. It is a morning's work and it makes the
  navigation the one thing the server cannot change, which for a backend-driven product is the
  wrong thing to be proud of.
- **Not covered:** deep linking from outside the app, a back stack that survives process death, and
  the canvas's profile rows that name features this product does not have — payment methods,
  auto top-up, language. A row that draws a feature nobody built is a mockup, not a product, and
  this item draws what konekt actually knows.
- The canvas contradicts itself about the tabs and it is worth knowing before reading it as a
  specification: section 01 draws four, section 05 draws three (`Home · Plans · Profile`). Four is
  taken, because Orders exists and is otherwise unreachable.

- AC: every tab screen carries the bar, the current tab is marked, and pressing one does not grow
  the back stack — a tab is a destination, not a step.
- AC: the tab set, their labels and their order come from the server, and changing them is a
  redeploy rather than a client build. Shown by serving a different set to brand B.
- AC: the conformance walk visits an endpoint of kind `graph`, the `navigation` check records a
  non-zero counter, and its entry leaves `KONEKT_CHECKS_WITH_NOTHING_TO_VISIT`.
- AC: `Main.kt` holds no `Map<deeplink, address>`; the client resolves a deeplink through the graph
  it fetched.
- AC: a Profile screen exists, reachable by its tab, carrying what this product knows about an
  account — the number, the eSIMs, and signing out.
- Anchors: `shared/components/`, `server/src/main/kotlin/io/konekt/screens/`,
  `client/src/commonMain/kotlin/io/konekt/client/app/`, `docs/design/design-app-canvas.md`.

Background: [B-45](B-45-the-client-draws-one-screen-of-four.md) is where the route map was written and
where `kompot-navigation` was deferred — citing research §1.11, which is about `call.respond` dropping
a discriminator and says nothing about navigation. The decision was real; the reference was not.

## What landed

The bar, the tabs, the profile screen and the conformance walk's `graph` endpoint arrived earlier.
What closed the item is the last criterion: **`Main.kt` holds no route table, and a deeplink resolves
through the graph the client fetched.**

`KonektApp` asks `ScreenSource.navigation()` and merges the answer over what it opened with. It asks
**at a session boundary and nowhere else**, which is exactly when the answer can change: the graph
sits behind the user tier, so before a session it refuses, after one it is available, and after
signing out it refuses again. `Destination.startsOver` is the runner's own word for that moment — the
holder still does not learn what a token is.

A refusal is `null` and keeps what was there, rather than emptying the table: a deployment that serves
no graph is a coherent thing to be, and an application that lost every destination on one failed
request would be worse than one that never asked.

## What is left in the client, and the two different reasons

**The login screens**, because the graph cannot be asked before there is a session and those two ARE
the way in. That is a bootstrap and it stays one — a screen added there that is not part of signing in
is a screen the graph should have named.

**`app://order/<id>`, and that one is an upstream gap.** It is parameterised, and `kompot-tck` follows
every route of a served graph to its endpoint exactly as written, substituting nothing: the prefix
answered 404 and so did the pattern, both measured. A graph carrying it is a graph the walk reports as
broken; a graph without it is a history whose rows open nothing. Recorded as
[U15](../research/research-upstream-proposals.md) and written where the entry is, so it goes rather
than being inherited.

## The guard that replaced the guard

`NavigationGraphMatchesTheClientTest` held the served graph against the client's copy — and this item
deleted the copy, so it had no subject left. What it was worth moved into
`EveryScreenIsReachableTest`, from the other direction: **a `navigate` the server emits that the
client cannot resolve.** With one table left that is the only way the failure can happen, and it is now
the failure the walk names. Proved by mutation: removing `top-up` from the graph makes it name the
deeplinks that resolve to nothing — which before this change would have been three dead buttons and
one red test elsewhere.
