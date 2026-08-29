---
id: B-83
title: "Two documents promise typography from the server; no kit carries any and a font family cannot cross the wire"
status: done
priority: P2
size: XS
stage: stage-m6-reframe
---

# B-83 — The same word means three different costs and is priced as one

`operator-boundaries.md`'s first row and `README.md`'s summary of it both read *colours and
typography → server deploy, no client rebuild*. Under that one word sit three axes with three
different costs, and `design-brand-kit.md:36-38` — the more careful document — already separates
them:

| Axis | Actual cost |
|---|---|
| Colours | server deploy. True, demonstrated, and the whole of what is proved |
| The type **scale** (sizes, weights, letter spacing) | it could travel — `KompotTheme` carries it — but **neither kit contains a `typography` block**, so nothing in this build has ever done it |
| The **font family** | not available at any price. `KompotTextStyle` carries size, line height, weight, letter spacing and colour and no family, so a face named by a server would not arrive |

A reader pricing a rebrand reads "typography" and thinks "the operator's face". That is the one of
the three that cannot be bought, and it is priced in the same cell as the one that can.

- **The decision: say colours, and price the other two separately** — the scale as *available and
  unused*, the family as *not available*, which is the fifth cost
  [B-81](B-81-the-boundaries-table-has-no-row-for-language.md) adds to the table.
- **The rejected alternative is to ship a `typography` block in one kit to make the word true.**
  That is a real change to a real screen for the sake of a sentence, and the sentence would still be
  wrong about the family.
- This item does **not** propose an upstream ask for a font-family token. That may be worth one —
  `research-upstream-proposals.md` is where it would go — but it is a separate judgement about
  somebody else's toolkit.

- AC: no document in this repository prices "typography" as one axis. `README.md` and
  `operator-boundaries.md` agree with `design-brand-kit.md`, which is the document that read the
  toolkit.
- Anchors: `docs/services/operator-boundaries.md`, `docs/design/design-brand-kit.md`, `README.md`,
  `server/src/main/resources/themes/brand-a.json`, `server/src/main/resources/themes/brand-b.json`,
  `client/src/commonMain/kotlin/io/konekt/client/theme/KonektDesignSystem.kt`.

## What was done

One row became three, in both documents that had it as one:

| Axis | Cost now |
|---|---|
| Colours | server deploy — the axis the rebrand is actually demonstrated on |
| The type **scale** | server deploy, **never yet done**: `KompotTheme` carries a `typography` block and neither kit in this build contains one |
| The **font family** | **not available** — the fifth cost [B-81](B-81-the-boundaries-table-has-no-row-for-language.md) added |

Verified rather than recalled: `brand-a.json` and `brand-b.json` have exactly four keys each — `id`,
`displayName`, `light`, `dark` — so no typography block has ever crossed this wire, and §1.2's fact
table records `KompotTextStyle` as size, line height, weight, letter spacing and colour with no family.

Two edits past the AC:

- **README gained the icons row too.** It now has two `not available` axes and the summary table no
  longer implies everything on it has a price — the same argument `B-81` made for the full table.
- **D2 in `research-architecture` is amended, not rewritten.** It decided *colour, typography and
  every string ship from the server*, which overstates the same word. The amendment says what
  "typography" meant there — the scale, not the face — and points at the priced table. Research
  records what was decided; it is amended at the point of divergence rather than edited to look like
  it was always right.

## What is deliberately not in scope

Shipping a `typography` block in one kit to make the word true. That is a real change to a real
screen for the sake of a sentence, and the sentence would still be wrong about the family. Also not
in scope: an upstream ask for a font-family token — that is a judgement about somebody else's toolkit
and belongs in [research-upstream-proposals](../research/research-upstream-proposals.md) if it is
made at all.

## Anchors

| What | Where |
|---|---|
| The three-way split | `docs/services/operator-boundaries.md`, `README.md` |
| The document that read the toolkit | `docs/design/design-brand-kit.md` |
| The kits, with no typography block | `server/src/main/resources/themes/brand-a.json`, `brand-b.json` |
| The decision it amends | `docs/research/research-architecture.md` D2 |
