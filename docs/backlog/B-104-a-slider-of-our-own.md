---
id: B-104
title: "A slider, as konekt's own dictionary extension — the builder's quantities are a range, not a list"
status: open
priority: P2
size: M
stage: stage-m7-completeness
---

# B-104 — The component the toolkit does not have, added deliberately rather than worked around

The custom package builder draws three `select_input`s. That was not a design choice: kompot's
standard field set is text, amount, checkbox, autocomplete and selection, and
[B-87](B-87-the-custom-package-cannot-be-bought.md) closed the question by rejecting a slider —

> kompot has no slider component, and `CustomPackageForm.kt` says so; three `select_input`s is what
> the toolkit has and the honest thing is to keep them and stop describing the feature as sliders
> anywhere.

**That decision is now reversed on purpose, and the reason is that konekt has a dictionary of its
own.** Nine component types here are konekt's, not kompot's — `plan_card`, `usage_counter_card`,
`banner`, `surface` and the rest — each added because the toolkit's vocabulary did not carry the
product's meaning. A quantity chosen from an ordered range with a price that moves as it moves is
exactly that case, and a dropdown is the workaround.

## What this costs, said before it is started

**A client release, and the dictionary is the API.** That is `operator-boundaries.md`'s most expensive
row and it applies in full: a wire type nothing renders is a blank on the screen, and every client —
desktop, Android, iOS — must learn it before a server may send it. This item is not "add a widget"; it
is "add a word to the language", with the compatibility that implies.

**The degradation path is what makes it safe to ship.** An unknown component draws konekt's own block
and reports to the sink, so a client that predates the word shows a labelled hole rather than nothing —
and the server can go on sending `select_input` to clients that have not learned it, because the form
schema is served per request.

## The decision

- **`slider_input`, in `:shared:components`, registered by KSP like the other nine**, in
  `konektWireNames` and `konektDictionary`, with the round-trip test every dictionary type has.
- **It carries the STEPS, not a min/max/increment.** The server already prices from a fixed list per
  quantity — `CustomPackageTariff.DATA_GB_STEPS` and its two siblings — and a component that offered a
  continuous range would let a client propose a size the server refuses. Same list on both sides,
  which is the rule the selects already follow.
- **It patches like the selects do**, through `triggersPatch` and the form controller: the price is
  the server's and travels back in a `FormPatch`. This item does not change where the price comes
  from — see [B-101](B-101-the-form-never-asks-the-server-to-recompute.md), which is why the price
  does not move today at all.
- **Rejected: rendering a slider client-side over the existing `select_input`.** It would look right
  and lie about the wire: the server would still be describing a dropdown, and the next client would
  draw one.

## Acceptance criteria

- AC: the builder's three quantities are sliders on desktop, Android and iOS, each snapping to the
  steps the server prices.
- AC: the price moves as the slider moves, without the form resetting — which needs
  [B-101](B-101-the-form-never-asks-the-server-to-recompute.md) done first, and this item says so
  rather than claiming a moving price on its own.
- AC: a client that does not know the type draws the unknown block and reports it, verified rather
  than assumed — the same check the other nine have.
- AC: `konektWireNames` and `konektDictionary` agree, and the registration round-trip test covers it.
- AC: the canvas gains the component, or the item records that the canvas does not draw a slider and
  what was drawn instead. **The canvas does not draw the builder at all today**, which is worth saying
  out loud: the word "slider" entered this repository through `B-20`'s acceptance criteria and not
  through a design.
- AC: `B-20`'s "AC MET: moving a slider updates the price" is corrected either way. It was never true:
  there was no slider, and the price did not move.

## Anchors

| What | Where |
|---|---|
| The dictionary this joins | `shared/components/src/commonMain/kotlin/io/konekt/components/` |
| The steps both sides must share | `server/src/main/kotlin/io/konekt/packages/CustomPackageTariff.kt` |
| The form as served today | `server/src/main/kotlin/io/konekt/packages/CustomPackageForm.kt` |
| What a new component costs | `docs/services/operator-boundaries.md` |
| The claim to correct | `docs/backlog/B-20-custom-package-builder.md` |
