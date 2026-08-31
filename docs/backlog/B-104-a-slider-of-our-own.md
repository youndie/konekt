---
id: B-104
title: "A slider, as konekt's own dictionary extension — the builder's quantities are a range, not a list"
status: done
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

## What was done

`slider_input` is the twelfth name in konekt's dictionary. Component in `:shared:components`,
registered in `konektWireNames` and `konektDictionary`, renderer in the client, and the server sends
it where it sent three `select_input`s.

**The steps travel, not a range.** `SliderInputComponent.steps: List<String>` is the tariff's own step
list — `0, 1, 5, 10, 20, 50` for data — so the control cannot express a quantity the price function
refuses. A range with an increment would have made every intermediate position a refusal the
subscriber meets at submit rather than at the control.

**It is the first renderer here that WRITES into the form.** Every renderer already receives the
`FormController`; this one reads the field through `getFieldFlow` and writes through
`onValueChanged` + `requestPatchIfNeeded`. Both calls, in that order: a field that `triggersPatch`
still has to say when it has settled, and the toolkit does not infer the second from the first.

**The value comes from the controller, never from local state.** A slider holding its own position
drifts from the form the moment a patch, a reset or a validation moves the field — and it is the
controller the submit reads.

**Compose counts the stops BETWEEN the ends**, so a six-value list is `steps = 4`. Off by one there
puts a stop where no price exists, which is why the assertion that the component's steps equal the
tariff's is now worth more than it was: a select offered a list and could only offer what it was
given; a slider offers positions.

## Verified

- Round-trips through `generatedKonektSerializersModule` like the other eleven —
  `KonektRegistrationTest` green with the dictionary at twelve.
- Driven end to end against a running stand: the served form carries three sliders with the tariff's
  steps, and `CustomPackageFormStandTest` moves the first one to position 3 through the semantics
  action a drag ends up calling, then asserts the server's `$15` arrives **and** that the slider still
  reads `10 GB` — the half a refetch would fail.
- `:server:test` green; the desktop application restarted on it.

## Two tests had to learn the new word, and one of them says why that matters

`CustomPackageFormTest` asserted that every declared field is rendered by a component naming it, by
matching on `SelectInputComponent`. The day the selects became sliders that assertion did not
complain about a slider — it reported the three quantities as **declared and never rendered**, which
is the same message it would print if the inputs had been deleted. A guard that enumerates the shapes
a thing may take fails the same way whether the shape changed or the thing vanished.

## What was NOT done

**The canvas still does not draw the builder.** The word "slider" entered this repository through
`B-20`'s acceptance criteria and never through a design, and that is unchanged: what exists now is a
component chosen for a reason, not a drawing implemented. `B-20`'s "AC MET: moving a slider updates
the price" is corrected in that item — it was false twice over, and it is worth leaving the correction
rather than the claim.

## Anchors

| What | Where |
|---|---|
| The component | `shared/components/src/commonMain/kotlin/io/konekt/components/SliderInputComponent.kt` |
| The dictionary | `KonektWireNames.kt`, `commonTest/.../KonektDictionary.kt` |
| The renderer | `client/src/commonMain/kotlin/io/konekt/client/render/SliderInputRenderer.kt` |
| What the server sends | `server/src/main/kotlin/io/konekt/packages/CustomPackageForm.kt` |
| Driven through the real control | `client/src/jvmTest/kotlin/io/konekt/client/stand/CustomPackageFormStandTest.kt` |
