---
id: B-20
title: "The custom package builder as a form, with the price coming from the server"
status: wip
priority: P2
size: M
stage: stage-m3-product
epic: feature-buy-package
blocked_by: [B-08]
---

# B-20 — The custom package builder as a form, with the price coming from the server

Three quantities — gigabytes, minutes, messages — and a price that changes as they move. This is the
one screen where `form-core`'s split earns its keep: validation, visibility and cross-field rules run
on the client, and only a server-relevant change asks the backend for a patch.

- **The decision and its reason.** Bounds and steps are validated locally; the price is a patch from
  the server, because a price computed on the client is a price a client can argue with. kompot's
  readme is explicit that limits and balances belong to the server and the client only highlights the
  field it names.
- The rejected alternative is sending a price table to the client. It is fewer round trips and it puts
  the tariff in the app bundle, where changing it is a release.
- Not covered: promotional pricing. One tariff function, no campaign layer.

- AC: moving a slider updates the price without the fields losing focus or resetting.
- AC: a combination the balance cannot cover is refused by the server and the balance field is the one
  highlighted.
- Anchors: `server/src/main/kotlin/io/konekt/packages/CustomPackageForm.kt`.

Background: [research-architecture](../research/research-architecture.md) §1.5.

## What landed

Three quantities as SELECTION fields with a server-priced tariff, a form endpoint answering a
`KompotFormResponse`, and a refusal for any size the package does not come in. It is the first thing
in this build to use `form-core`, `form-standard` and `kompot-forms` at all — and the first endpoint
of kind `form`, which took the conformance kit's `form-fields` check out of the declared-empty list
the moment it landed.

**Quantities are a choice from a list rather than a slider, and that is the wire's shape.** kompot's
standard field set is text, amount, checkbox, autocomplete and selection: there is no slider and no
numeric range. It suits a tariff, which sells packages rather than arbitrary numbers, and the steps
the client offers are the same list the server prices — one list, so a client cannot offer a size the
server refuses.

## The patch mechanism cannot be used, and the kit is what proved it

B-20 exists to demonstrate `form-core`'s split: local validation, and a price patched in from the
server without redrawing. **That half is blocked upstream.**

`FormPatch` updates values in the `FormController`. Only bound components read the controller, and
every one of them is editable; the single non-editable display, `read_only_field`, is explicitly not
bound — its renderer draws `component.value` and never touches the controller it is handed. So a
server-computed value is *either editable or stale*. Filed as
[youndie/kompot#89](https://github.com/youndie/kompot/issues/89).

The first attempt declared `price` and `balance` as schema fields anyway, with a `MaxAmountRule`
reading the balance out of a neighbouring field's metadata — the pattern the readme describes. The
conformance walk refused it the moment it had a form to look at:

```
[form-fields] /api/v1/forms/custom-package — field "balance" is declared but never rendered
[form-fields] /api/v1/forms/custom-package — field "price" is declared but never rendered
```

and it was right: SPEC §9.2 asks that every declared fieldId have a component rendering it, and a
schema declaring a field nothing renders is a schema that lies about its own form. **That is the kit
earning its keep on the first form it ever saw** — the defect was an hour old and invisible from every
test written for it.

So the two computed values are not fields. The form is refetched with what has been chosen, the price
and the balance are server-rendered read-only values, and the affordability refusal is a sentence
beside the balance rather than a `focusOn`.

- AC NOT MET: "moving a slider updates the price without the fields losing focus". A refetch redraws.
  It needs a bound, non-editable field — kompot#89 — and no arrangement of what exists today avoids it.
- AC PARTLY: the server refuses a package the balance cannot cover and says so beside the balance.
  What it cannot do is *highlight the field*: `FormPatch.focusOn` names a fieldId, and the balance
  cannot be one for the reason above.

## Two things the client half taught, both about registration

A `FormSchema` carries polymorphic **field definitions** and the tree carries their **components**,
registered in two different modules. A client with only `generatedFormsSerializersModule` decodes the
screen and fails on `$.schema.fields[0]`. Both halves are needed on both sides, and the stand suite
found it the first time it asked for a form.

And the fourth scenario in that suite did not run: it ended in `assertNotNull`, which returns a value,
so the method was not void and JUnit ignored it. `B-42`'s guard named it — `declares 4 @Test and JUnit
ran 3` — a few hours after that guard was written, on the code of the person who wrote it.
