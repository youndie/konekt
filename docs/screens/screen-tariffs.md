---
id: screen-tariffs
title: Tariff catalogue and one change — the confirmation a subscriber has to give
type: client_screen
platform: [jvm, android, ios]
status: active
entry:
  jvm: "GET /api/v1/screens/tariffs and GET /api/v1/screens/tariff-changes/{changeId} — server-built trees; there is no client-side screen class"
parent_feature: feature-tariff-change
calls_api:
  - api-openapi
source: server/src/main/kotlin/io/konekt/tariff/TariffScreens.kt
---

# Screen: the tariff catalogue, and one change

> Two trees, and the second is the point. A purchase's confirmation asks *spend this?*; this one asks
> *change what you are on?* — and it is the only screen in the build where two facts are true at once
> and both have to be on it: the tariff a subscriber is on, and the one they are moving to.
>
> Read out of the source on 2026-08-29.

## 0a. Code anchors

| What | File |
|---|---|
| Both trees | `server/src/main/kotlin/io/konekt/tariff/TariffScreens.kt` |
| Their routes | `server/src/main/kotlin/io/konekt/tariff/TariffScreenRouting.kt` |
| The way in | `server/src/main/kotlin/io/konekt/screens/ProfileScreen.kt` |
| The graph entry | `server/src/main/kotlin/io/konekt/screens/Shell.kt` |
| What the client does with the actions | `client/src/commonMain/kotlin/io/konekt/client/app/ChangeTariff.kt` |

## 0. Entry point and visibility

`app://tariffs`, reached from the **profile** tab, which is where a subscriber looks for what they are
on. Not a tab of its own: the canvas has four, and a fifth would be a change to the shell for a screen
opened rarely.

One change is `/api/v1/screens/tariff-changes/{changeId}` and has **no deeplink**. Nothing navigates
to a change — it is reached by an action whose answer carries the id — and a constant used by nothing
is the shape this repository files as a defect.

Both are behind the user tier. The change screen's owner check is in the use case: a stranger's change
answers **404**, not 403, because refusing differently would confirm that it exists.

## 1. Screen states

### The catalogue

| State | What is on screen |
|---|---|
| ordinary | every tariff as a `plan_card` — title, price *per month*, allowance. The current one carries the badge **Your tariff** and no action; every other one carries `change_tariff` |
| a change is waiting | a banner naming the tariff the change is to and the date it takes effect, with **Review it** — and **no tariff offers a change**, because the server answers 409 to a second one |

### One change

| State | Title | What is on screen |
|---|---|---|
| awaiting confirmation | *Confirm the change* | both tariffs, the date, "nothing changes until you confirm", and the **Confirm** button |
| confirmed | *Change confirmed* | both tariffs, the date, "you stay on your current tariff until …", and **no control** |
| refused | *Change refused* | "the change could not be made and you stay on your current tariff. Nothing was billed" |
| reversed | *Change reversed* | "the change was reversed …". A different sentence from refused, because a subscriber told only that something did not work cannot tell whether trying again is worth anything (`B-68`) |
| still processing | *Your change* | "this change is still being processed" |

## 2. API integration

The catalogue is a GET. A press sends `change_tariff`, which the client posts to
`POST /api/v1/tariff-changes`; the answer carries the change id, and the client builds the change
screen's address from the `@Resource` pattern and refetches. **Nothing asserts on the POST's body** —
the client discards it, so anything asserted there is asserted about a payload nothing renders, which
is the rule `B-66` cost.

Confirming sends `confirm_tariff_change` and ends on the same address in a different state.

## 3. UI elements, top to bottom

**Catalogue:** the title *Your tariff*; the pending banner if there is one; a card per tariff, in
catalogue order; the bottom bar with **Profile** current.

**One change:** the title, which states the outcome; *Now on* / *Changing to* / *Takes effect* as
label-and-value rows; a banner saying what happens next; and the confirmation, when one is wanted.

There is **no way-out button**. The bottom bar is the way out of every screen that is not a flow, and
a second primary beside the confirmation is what `B-71` removed from the purchase result.

## 4. Navigation (summary)

`profile → app://tariffs → (change_tariff) → one change → (confirm_tariff_change) → the same change`.

## 5. Quirks

- **The current tariff is `available`, not `sold_out`.** `sold_out` makes a card unpressable and also
  makes the client draw the words **Sold out**, in red, in the badge's slot — over the subscriber's
  own tariff. What makes a card unpressable is `action == null`. Found by a screenshot from a device;
  every tree assertion had passed over it.
- **No new component type.** A tariff is a `plan_card`. A `tariff_card` would be a client release for
  a card that differs from an existing one in nothing but the word.
- **There is no success tone.** The vocabulary is `info`, `low`, `error`; a confirmed change uses
  `info` and the words carry the outcome. Inventing a fourth tone would be a client release for a
  banner's colour.
