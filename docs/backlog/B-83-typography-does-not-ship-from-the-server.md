---
id: B-83
title: "Two documents promise typography from the server; no kit carries any and a font family cannot cross the wire"
status: open
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
