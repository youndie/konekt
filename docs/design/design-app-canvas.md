---
id: design-app-canvas
title: konekt — the application design canvas
type: design
status: active
date: 2026-08-25
---

# The design canvas, and what it commits konekt to

Source: Claude Design project *Mobile app for konekt*,
`https://claude.ai/design/p/d76fd006-5a1d-46ca-80f4-c3fd151f76c7`, file `Konekt eSIM app.dc.html`.
A copy of the canvas markup is kept beside this document as
[`konekt-esim-app.dc.html`](konekt-esim-app.dc.html); it needs the project's `support.js` to render,
so the canvas link above is the live one and the copy is the record.

Eight sections, 393×852, light and dark side by side, every state as its own frame:

| # | Section | What it fixes |
|---|---|---|
| 01 | Home & balance | the counter card, its normal / low / exhausted states, the balance block |
| 02 | Plans catalog & plan detail | four list states in one frame: available, tonal alternate, sold out, loading skeleton |
| 03 | Purchase — confirm, processing, success, rollback | the four states of a saga, including the compensated one |
| 04 | eSIM install — frame by frame | four steps plus the QR frame in dark, and the slot-limit failure |
| 05 | Orders & profile | the order row, the refund line, the support block |
| 06 | Component dictionary — all states | every control in every state, including the unknown-component block |
| 07 | App icon — sketches | vector sketches, explicitly not finished artwork |
| 08 | Brand B parity | the same markup on an ink palette with tighter radii |

## What the canvas already knows about the stack

The designer worked against the toolkit rather than around it, and three of the notes on the canvas
are findings in their own right. They are verified in
[research-architecture](../research/research-architecture.md) §1.2 and §1.5:

- colour swatches carry the wire token names of `kompot-ds-material` — `primary`, `primary_container`,
  `secondary`, `surface_variant` and the rest of the Material 3 roles;
- the form frames use the real field types of `kompot-forms` — `text_input`, `amount_input`,
  `autocomplete_input`, `checkbox_input`, `radio_group`, `select_input`, `read_only_field` — and every
  one of those exists on the wire;
- *"radii are a client build constant — the server theme carries colours and typography only, so brand
  B's shape change needs a client release."* Verified, and it is a deliberate property of kompot
  rather than a gap. It is the reason for decision D2.

Section 08 exists to prove the layout survives the shape swap: brand B changes `lg` 36→22, `md` 20→12
and turns pills into rounded rectangles, and nothing in the layout depends on it. That is what makes
a client-side shape constant cheap rather than dangerous.

## The component dictionary konekt has to own

Everything below is drawn in section 06 and has no wire type in the toolkit. These are konekt's own
components, in one KSP module with its own `kompotModuleTag`. **Built and fixed in `B-03`** — the
names below are what is on the wire, and `shared/spec/schema/konekt-components.schema.json` is
generated from the types themselves.

| Canvas element | Proposed wire type | Why it is not a toolkit component |
|---|---|---|
| counter card with progress | `usage_counter_card` | a quota, a remainder and a projection in one control; the low and exhausted states change the copy, not only the colour |
| plan card | `plan_card` | price, quota triple, zone and availability state, including sold out and skeleton |
| QR block | `esim_qr` | renders an activation code as a QR locally; the code never becomes an image on the server |
| eSIM card | `esim_card` | ICCID in the mono face, lifecycle status, the actions each status permits |
| order row | `order_row` | reference, date, amount, and a refund line that reads in money |
| banner | `banner` | info / low / error, inline in the flow |
| snackbar | `snackbar` | transient, outside the tree |
| step meter | `step_meter` | "step 3 of 4"; the wizard's own progress, not a generic progress bar |
| skeleton | `skeleton` | the loading state of a list row, drawn rather than absent |
| unknown block | replaces `UnknownComponent` | not a new wire type — a replacement renderer, see research §1.4 |

Nine, not ten. The tenth was a switch, and it is a toolkit component after all:
[kompot#82](https://github.com/youndie/kompot/issues/82) closed on 2026-08-25 and
`CheckboxInputComponent` now carries `variant`, with `KompotCheckboxVariants.SWITCH` the word the
standard renderer acts on. A settings toggle is `checkbox_input` with that variant, and no component
of ours.

The nine carry **pre-formatted text**, not numbers with units — `valueText` is `"15,8 GB left"` and
`priceText` is `"1 190 ₽"`. That is the backend-driven bargain taken deliberately: the server builds
the screen, so the server formats, and a client that cannot format money cannot format it
inconsistently. The exceptions are geometry rather than language — a counter's `progress` fraction and
a step meter's two integers — because a bar and a "3 of 4" cannot be drawn from a sentence.

## What the canvas asserts that the build must be able to reach

Three frames describe states that a naive implementation can never enter, and each is a backlog item
rather than a picture:

**The rollback frame.** *"The provider declined the operation. We reversed the hold — your balance is
back to where it was, and no eSIM was issued."* Stated in money, with the reference to quote to
support. This is the compensated branch of the purchase saga, and it is the reason the payment mock
takes a refuse switch (research D10).

**The slot-limit failure on install step 1.** Eight-profile limit reached — named on the canvas as
*"the failure this flow actually hits in the field"*. The SM-DP+ mock therefore has to be able to
refuse for that reason specifically, not merely to fail.

**The unknown-component block.** *"The server sent a component this build does not know. Everything
around it still works — update to see it."* Two densities: a full card when the block is the screen's
subject, one line when it sits among known rows. The canvas labels the example
`type: esim_transfer_widget`. A client that registers everything the server sends can never draw this
frame, which is Risk 5 in the research and its own backlog item.

## Typography and shape

Manrope for the interface, Space Grotesk for figures, ICCIDs and activation codes. Both are Google
Fonts and both must be bundled in the client rather than fetched — `KompotTextStyle` carries size,
weight, line height, letter spacing and colour, and no font family, so a face named by the server
would not arrive anyway.

Spacing 4 · 8 · 12 · 16 · 20 · 24, minimum touch target 48. Shape scale per brand: A is `lg` 36 /
`md` 20 / `sm` 12 with pills; B is 22 / 12 / 8 with rounded rectangles.

## What the canvas does not provide

The app icon is vector sketches, and the canvas says so: not finished artwork. A real icon set is an
illustrator's job or a supplied brand mark. It is a backlog item, not an oversight, and it is not on
the critical path of anything.
