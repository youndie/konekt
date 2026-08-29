---
id: B-79
title: "The repository calls itself a box an operator buys, and it is a reference implementation on a telecom domain"
status: done
priority: P0
size: M
stage: stage-m6-reframe
---

# B-79 — The claim on the first line is the one thing no test can check

Every entry point says *box*. `README.md` and `docs/README.md` open with "a white-label subscriber
account for an eSIM MVNO"; `backlog.md`'s own title is "a white-label eSIM account that proves the
stack carries it"; `CLAUDE.md` opens the same way. A reader arrives expecting something an operator
can take, and what is here is a reference build of six toolkits that uses a telecom domain because
that domain loads them honestly.

The distance between the two is not a matter of polish, and it is measurable:

| What a box needs | What is here |
|---|---|
| An application in the stores | No `androidTarget` in any `*.gradle.kts` in this repository, and an iOS `.app` assembled by `scripts/ios-home-app.sh` for the **simulator** |
| A catalogue an operator maintains | Four plans and three tariffs as `val`s in Kotlin — `StaticPlanCatalog`, `server/src/main/kotlin/io/konekt/tariff/TariffData.kt` |
| A surface to run it from | No admin route, no management API, no CLI. A subscriber row is created by their first successful sign-in |
| A seam under the billing | Balance, ledger and counters are konekt's own Postgres tables. An MVNO's OCS owns those |
| More than one operator per install | No tenant column in any of the eleven migrations |

None of those is a defect **of a reference implementation**. Every one of them is a defect of a box.
So the cheapest correct move is not to build them: it is to stop claiming them.

- **The decision: restate the product as a reference implementation on a telecom domain, and keep
  the white-label mechanism as a demonstrated property rather than as the offer.** The
  backend-driven rebrand is real and worth showing — [B-22](B-22-brand-b.md) proves a second brand's
  palette ships from the server and the client applies it without a rebuild. What is not real is
  the sentence a buyer reads into it.
- **The rejected alternative is to build towards the box** — a catalogue in the database, an admin
  surface, ports under a real BSS. It is a year of work whose result is a worse version of a product
  several vendors already sell, and every hour of it is an hour not spent on what this repository is
  actually good at: showing what six toolkits cost on a domain that loads them.
- **The second rejected alternative is a disclaimer at the bottom.** A claim in the title and a
  correction in the last section is how a reader ends up quoting the title.
- This item does **not** rename anything in the code, does not touch `operator-boundaries.md`'s
  table (its rows are correct — see [B-81](B-81-the-boundaries-table-has-no-row-for-language.md)),
  and does not write the non-goals down; that is [B-80](B-80-the-non-goals-are-nowhere.md).

## What "reference implementation" has to mean here, or it is just a softer word

Three claims survive the reframe and they are the ones worth leading with, because each is measured
in this repository rather than asserted:

1. **Six toolkits carrying real load.** A saga that compensates money, a broker under an outbox, a
   component dictionary that is the API, three observability tools reporting from a deployed
   instance.
2. **The cost of each one written down.** 17 database writes for a six-interceptor saga; a client
   release for a corner radius; a broker restart for a topic.
3. **The failures a green build does not show.** petich dropping events silently, the conformance
   kit passing vacuously, an Apple target nobody published — each with the guard that now catches it.

A telecom domain is the *fixture*, not the offering. That sentence is the whole of the change.

- AC: `README.md`, `docs/README.md`, `backlog.md` and `CLAUDE.md` each open by naming a reference
  implementation, and none of them uses "box", "white-label account" or "operator buys" as the
  product's description. The white-label mechanism appears as one of the demonstrated properties,
  with the link to `operator-boundaries.md` beside it.
- AC: a reader who stops after the first paragraph of `README.md` cannot come away believing an
  operator can deploy this and sell service on it.
- Anchors: `README.md`, `docs/README.md`, `backlog.md`, `CLAUDE.md`,
  `docs/services/operator-boundaries.md`.

## What was done

Four entry points, one sentence each, and nothing in the code:

| File | Was | Is |
|---|---|---|
| `README.md` | *a white-label subscriber account for an eSIM operator — the operator rebrands the box* | *a reference implementation of six Kotlin toolkits, on the domain of an eSIM MVNO subscriber account — not a product an operator can deploy and sell service on* |
| `docs/README.md` | *A white-label subscriber account for an eSIM MVNO, built as a reference* | the same reframe, with the fixture sentence |
| `backlog.md` | *a white-label eSIM account that proves the stack carries it* | *a reference implementation that shows what the stack costs* |
| `CLAUDE.md` | *A white-label subscriber account for an eSIM MVNO* | the reframe, plus a pointer to the non-goals |

Three further edits the AC implies rather than names:

- **The first paragraph carries the denial**, not a later section. `README.md`'s opening now lists
  what is absent — management surface, editable catalogue, BSS seam, second tenant — and says in the
  same breath that none of it is a to-do. A reader who stops after that paragraph cannot come away
  believing an operator can sell service on this.
- **The rebrand became a demonstrated property.** `### 🎨 What "white-label" actually covers` is now
  `### 🎨 The rebrand, as a demonstrated property`, opening with what `B-22` actually proved — a
  second palette from the server, applied without a rebuild — and still linking `operator-boundaries`
  for the full price list.
- **`stage-m5-upstream`'s title lost the word too** — *Upstream and the box* → *Upstream and the
  boundaries*, which also rewrites the generated section header in the index.

And one correction that was not in the item: `CLAUDE.md` said *Compose Multiplatform on Android and
iOS*. There is no Android target ([B-85](B-85-the-client-has-no-android-target.md)) and there is a
desktop one, so the line named one platform that does not exist and omitted the one that does. It now
reads *desktop and iOS* — which is what the reframe makes it safe to say plainly.

## What is deliberately not in scope

The two claims this item leaves for the documents that own them: the non-goals themselves, which are
[B-80](B-80-the-non-goals-are-nowhere.md), and the rows `operator-boundaries.md` is missing, which
are [B-81](B-81-the-boundaries-table-has-no-row-for-language.md). Nothing here touched that table —
its fourteen rows are correct about what they cover.

## Anchors

| What | Where |
|---|---|
| The product's first sentence | `README.md`, `docs/README.md`, `backlog.md`, `CLAUDE.md` |
| The rebrand's price list, unchanged | `docs/services/operator-boundaries.md` |
| What the rebrand actually proved | `docs/backlog/B-22-brand-b.md` |
