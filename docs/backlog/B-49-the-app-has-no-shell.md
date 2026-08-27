---
id: B-49
title: "Four screens and no way between them except a banner"
status: wip
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
