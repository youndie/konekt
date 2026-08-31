---
id: B-20
title: "The custom package builder as a form, with the price coming from the server"
status: done
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

## The patch mechanism was blocked upstream, and now is not

B-20 exists to demonstrate `form-core`'s split: local validation, and a price patched in from the
server without redrawing. **That half was blocked, and the conformance kit is what proved it.**

The first attempt declared `price` and `balance` as schema fields, with a `MaxAmountRule` reading the
balance out of a neighbouring field's metadata — the pattern the readme describes. The walk refused it
the moment it had a form to look at:

```
[form-fields] /api/v1/forms/custom-package — field "balance" is declared but never rendered
[form-fields] /api/v1/forms/custom-package — field "price" is declared but never rendered
```

and it was right: SPEC §9.2 asks that every declared fieldId have a component rendering it, and a
schema declaring a field nothing renders is a schema that lies about its own form. **That is the kit
earning its keep on the first form it ever saw** — the defect was an hour old and invisible from every
test written for it.

The cause was upstream. `FormPatch` updates values in the `FormController`; only bound components read
the controller, and every one of them was editable. The single non-editable display,
`read_only_field`, was explicitly not bound — its renderer drew `component.value` and never touched
the controller it was handed. So a server-computed value was *either editable or stale*. Filed as
[youndie/kompot#89](https://github.com/youndie/kompot/issues/89) and **fixed in kompot 0.33.0**:
`read_only_field` takes an optional `fieldId` and is then bound for values and for visibility, and
editable by nobody.

So the shape is now the one the item asked for. Both computed values are declared **and** rendered;
the GET is the first paint; every change after it is `POST /api/v1/forms/custom-package/patch`
answering a `FormPatch` with two values and no tree. The refetch is gone.

- AC **NOT** MET, and it was recorded as met: *"moving a slider updates the price without the fields losing focus or resetting."* There was no slider — `B-87` records why, and the field set had none — and the price did not update at all, because the client drew every form with no patch fetcher (`B-101`). Both halves were false when this line was written. There is a slider now (`B-104`) and the price does move (`B-101`), which is what makes the correction worth keeping rather than deleting: the claim was made before either was true.
  `CustomPackageFormStandTest` renders the real form through the real registry against the running
  stand, chooses 10 GB, and waits for `$15` — then asserts the chosen quantity is **still 10**. That
  second assertion is the discriminating one: a refetch would also show the new price, and would not
  leave the selection standing.
- AC MET: "a combination the balance cannot cover is refused by the server and the balance field is
  the one highlighted." `FormPatch.focusOn` names the balance, which it could not do while the balance
  was not a field. A freshly-opened form says it in words beside the number as well, and the submit
  route refuses a third time — a rule the client evaluates is a rule the client can skip.

## What the client half cost, which the item did not anticipate

The client did not render forms at all. `kompot-forms` and `kompot-forms-client` were **test-only**
dependencies with a comment saying why: they were there to make the design-system comparison
discriminating, "not because this module renders forms yet". Four things had to change, and each was
found by the stand rather than by reading:

1. Both form modules moved to `commonMain`, and `form-core` and `form-standard` joined them — the
   components module does not bring them, because a form's wire and a form's logic are separable
   upstream.
2. `generatedFormsClientRenderers` joined `konektRegistry()`, unconditionally. A registry that differs
   between two screens is two clients.
3. `konektClientJson` gained **both** `generatedFormsSerializersModule` and
   `formStandardSerializersModule`. With only the first it decoded the screen and died on
   `$.schema.fields[0]` — the exact failure this item's earlier notes predicted, met in full.
4. `KonektFormScreen`, the one screen shape that needs more than a tree: it remembers a
   `FormController` keyed on the form id, so a patch changes values inside a controller that survives.

Two smaller findings, both in the last category of "written and never exercised":

**`rememberCoroutineScope()` was the wrong scope.** It inherits the composition's context, which is
`Dispatchers.Main` — a patch is network work with no business there. It also made the screen
untestable: a Compose harness with `kotlinx-coroutines-test` on the classpath and no `setMain` throws
on first access. The controller now gets a `Dispatchers.Default` scope cancelled by a `DisposableEffect`.

**No form renders in a Compose test without a main dispatcher.** The toolkit's bound components read
the controller through `collectAsStateWithLifecycle`, which collects on the lifecycle's main
dispatcher; Skiko's harness provides a frame clock and no `Dispatchers.Main`. The form threw before
drawing a single field. That is a precondition of testing a form, not a defect in one.

## Proved by mutation

The stand test went green quickly enough to distrust. Two mutations, both restored:

| Mutation | Result |
|---|---|
| the screen is given no `patchFetcher` | waits out the 15 s timeout and fails — the price never changes |
| the price component stops naming its `fieldId` (exactly the pre-0.33.0 behaviour) | fails — the patch lands in the controller and the component does not read it |

The second is the one worth keeping: it reproduces the state kompot#89 described, and the test fails
in it. So this feature genuinely rests on the upstream fix rather than merely coinciding with it.

## What was unchecked here is checked now, and upstream

This section used to say that the conformance kit read four endpoint kinds, that a `FormPatch` was
none of them, and that consequently **no protocol check verified that the fields a patch updates, and
the one it focuses, are declared**. That was the same class of defect `form-fields` catches on the
form itself, and it was silent: `FormController` keys by string, so a misspelling applies cleanly and
the screen simply stops updating. It was filed as
[youndie/kompot#93](https://github.com/youndie/kompot/issues/93), recorded as U13.

**Closed, and released in `0.33.1.91`.** The kit grew a fifth kind — `patch` — a
`TckConfig.patchEndpoints` pairing of the patch address with the address of the form it patches, and
`patchesNameDeclaredFields`, which fetches the form for the declared set and holds the answer against
it. So this endpoint leaves `KONEKT_UNWALKED_ENDPOINTS` and is the only one the walk reaches by a
pairing rather than by a blind GET.

Proved by two mutations rather than by the run being green. Dropping the patch's body from
`submitPayloads` makes the kit report "the patch was never asked for" rather than passing quietly —
which is the failure mode the whole coverage gate exists for. And misspelling `price` as `prise` in
the patch the SERVER builds makes it report "updates a field the form does not declare": the exact
defect this section used to say nothing could see. `CustomPackageFormTest` keeps its own assertion —
it is faster and it fails at the unit rather than at the stand — but it is no longer standing in for
anything.

## Two things the client half taught, both about registration

A `FormSchema` carries polymorphic **field definitions** and the tree carries their **components**,
registered in two different modules. A client with only `generatedFormsSerializersModule` decodes the
screen and fails on `$.schema.fields[0]`. Both halves are needed on both sides, and the stand suite
found it the first time it asked for a form.

And the fourth scenario in that suite did not run: it ended in `assertNotNull`, which returns a value,
so the method was not void and JUnit ignored it. `B-42`'s guard named it — `declares 4 @Test and JUnit
ran 3` — a few hours after that guard was written, on the code of the person who wrote it.
