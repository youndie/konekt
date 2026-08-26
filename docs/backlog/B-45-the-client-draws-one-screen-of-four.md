---
id: B-45
title: "The client draws one screen of a product that has four"
status: open
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
