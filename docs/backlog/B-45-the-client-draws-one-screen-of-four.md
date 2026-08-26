---
id: B-45
title: "The client draws one screen of a product that has four"
status: done
priority: P1
size: L
stage: stage-m3-product
epic: feature-client
blocked_by: [B-44]
---

# B-45 — The client draws one screen of a product that has four

The server builds four screens — home, the order result, the history page and the eSIM wizard — plus a
form. The client renders **one**. `:e2e` asserts the other three as JSON, which says the server
composed them and nothing about whether a subscriber can reach or read them.

Two things stand in the way and they are separable:

**Six of nine dictionary types have no renderer**: `plan_card`, `esim_card`, `order_row`, `snackbar`,
`step_meter`, `skeleton`. A screen made of them draws six red boxes today (`B-44`) or six degradation
blocks after it — either way, not the product.

**Nothing navigates.** `KonektApp` takes one address as a parameter, which was the right shape for one
screen and is the reason `NavigateAction("app://plans")` on the home screen's banner resolves to
nothing. The screen document says so outright: *"a destination that does not exist in this build"*.

- **The decision and its reason.** The renderers come first and the navigation second, because a
  navigator that reaches a screen of red boxes demonstrates less than a screen nobody can reach yet.
  And navigation stays a **map from address to fetch**, not `kompot-navigation`: research §1.11 records
  why this build does not use it, and one screen at a time with an address is still the shape — what
  changes is that the address becomes a value the holder can be handed again.
- The rejected alternative is drawing the screens with Compose written per screen. It would be faster
  and it would end the demonstration: the whole claim is that a screen is a server response, and a
  client with four hand-built screens has four places for the server's tree to be ignored.
- Not covered: a back stack, deep links, or an animation between screens. One screen at a time until a
  second one makes the question concrete — the same rule that kept the holder honest.

- AC: from the home screen, "See plans" reaches a plans screen the SERVER built, and the plans on it
  are drawn as plan cards rather than as blocks.
- AC: buying from that screen reaches the order result, and the four purchase states are each drawn —
  which is also what `B-28`'s canvas sections 02 and 03 are waiting for.
- AC: every one of the nine dictionary types is either drawn by a renderer or named in a list that
  says why not, and the list is empty of types any served screen sends.
- Anchors: `client/src/commonMain/kotlin/io/konekt/client/render/`,
  `client/src/commonMain/kotlin/io/konekt/client/app/KonektApp.kt`,
  `server/src/main/kotlin/io/konekt/screens/`.

Background: [research-architecture](../research/research-architecture.md) §1.11 for why there is no
navigation graph, [screen-home](../screens/screen-home.md) §5 for the destination that does not exist.

## What landed

**All nine dictionary types draw what they are.** Six had no renderer — `plan_card`, `esim_card`,
`order_row`, `snackbar`, `step_meter`, `skeleton` — and every string on every one of them is composed
by the server: `priceText` and `quotaTexts` are rendered as they came, because this client owns no
formatter for money or for gigabytes (D15).

**The transition exists.** `app://plans` has been on the home screen's banner since `B-07` with
nothing behind it; there is a plans screen now and the holder owns the movement. The address is state
seeded from the parameter, and a `navigate` whose deeplink is in `routes` moves it.

The handler had to move with it: a source constructed with its own handler cannot move the screen it
is a source for, so `render` takes one and `KonektScreenSource` no longer keeps it. Clearing the
realtime overlay on a move is not optional — it is keyed by component id, and two screens can carry
the same id, so an update recorded before a move would shadow a node on the screen after it.

**And buying works, which needed an action of its own.** `navigate` is a transition the server chose
in advance; buying creates something, and where the subscriber goes next is that order's screen. So
`buy_plan` is konekt's second verb, handled by the COMPOSITION ROOT rather than the holder — a screen
holder with an opinion about purchases is this application's holder rather than a reusable one. What
comes back is an address, and the holder moves to it exactly as it moves for a `navigate`.

`kompot-commands`' `perform` is the general version and was rejected for now: it means a dependency, a
`submit` endpoint answering a `KompotAction`, and the kit's perform check. Worth taking when a second
verb needs it; one verb does not make a vocabulary.

- AC MET: "See plans" reaches a plans screen the server built, drawn as plan cards rather than blocks
  — asserted against the stand, and watched on a phone.
- AC MET: buying from that screen reaches the order it created. The order screen for a subscriber with
  no money says the purchase could not be started and nothing was charged, which is the state a
  first-time subscriber pressing the first thing they see actually gets.
- AC MET: every dictionary type is drawn, and the "not yet rendered" list is empty for the first time —
  asserted rather than merely true, so a tenth type lands in one of the two lists or fails.

## Three defects the flow found, each invisible one layer down

**A `LaunchedEffect` keyed on the pending action cancels itself.** Clearing the action inside the
effect changes its own key, so the coroutine is killed before the request it was launched to make has
answered. Nothing looks wrong: the press registers, the effect starts, the screen never moves. Keyed
on a press COUNT now, which gives every press a key of its own and needs no clearing — mutation-proved
by putting the old shape back.

**The order screen carries the plan title too**, so "wait until the plan title disappears" waited out
its timeout on a screen that had already arrived. The assertion is on the order screen's own copy now.

**`buy_plan` reached a screen before it reached the wire profile.** The conformance walk refused three
actions on one screen the first time it saw the plans screen — `$.children[1].action: the value
matched none of the 2 variants` — while the spec golden test passed, because it asserted the OTHER
verb by name. It names both now. Actions are registered by hand (§1.13), so nothing generates a
reminder, and this is the second time that has cost something.
