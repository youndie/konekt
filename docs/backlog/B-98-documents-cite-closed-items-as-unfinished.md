---
id: B-98
title: "Twelve places describe work that is finished as still to do, and the class has no guard"
status: done
priority: P1
size: M
stage: stage-m6-reframe
---

# B-98 — The documentation's own failure mode, found for the third time

[B-82](B-82-the-brand-kit-document-says-the-theme-is-unwired.md) named this exactly: a paragraph
*written mid-item, true for the length of one commit*, left behind describing a state that no longer
exists. It fixed one instance and wrote down why no gate caught it — every path in the paragraph was
valid, and only the claim about them was false, so `code_anchors.py` cannot see this class at all.

Twelve more are in the tree today, and the newest of them were created **by the items that closed
last week**. That is the argument for a guard rather than another correction: the class reproduces
faster than it is found by reading.

## The instances, read out of the files

**A document citing an item that has since closed.** This half is machine-checkable, which is the
point of separating it.

| Where | What it says | What is true |
|---|---|---|
| `docs/services/reference-scope.md:51` | the chart guard "is `B-91`, **and it is not built**" | `B-91` is `done`; `charts/konekt/templates/server.yaml` refuses any `replicas > 1`, and `scripts/chart-check.sh` proves each refusal names its own reason |
| `docs/services/reference-scope.md:61-64` | the tariff change and the custom package are "**unfinished**, not scoped out — `B-86`, `B-87`" | both `done`, both with screens, destinations and e2e scenarios |
| `docs/services/konekt-server.md:181` | the sweeper's second pass "does nothing. **The wasted work is** `B-92`" | `B-92` is `done` — `ClaimedSweep`, `V12__saga_sweep_claim.sql`, `ClaimedSweepTest` |
| `docs/services/konekt-server.md:183` | `B-91` cited as the standing consequence of the in-memory bus | same as above |
| `docs/services/konekt-server.md:185` | "**Only one of the five** is refused by the chart" | two are, since `B-91` |
| `docs/services/konekt-server.md:50` | "Generated schema: none. `B-23` is the item, and **until it closes** …" | `B-23` closed; `docs/api/openapi.json` is generated, committed and compared by the build |
| `docs/services/operator-boundaries.md:59` | "There is **no application to name yet** … See `B-85` and `B-90`" | `androidApp/src/main/AndroidManifest.xml` carries `android:label="konekt"`. The cost in that row — a client release — is still right; only its reason expired |
| `README.md:44` | "the Compose client is **a library with no application around it yet**" | `:androidApp` builds an APK, and `reference-scope.md:49` says in the same tree that Android *runs*, on a physical Pixel |

**A count written out in prose.** Countable, and wrong in four places.

| Where | Says | Is |
|---|---|---|
| `README.md:46` | "the **three** that are stopped on a stated cause" | two open (`B-77`, `B-97`) and one dropped (`B-90`) |
| `docs/README.md:31` | "four features, four screens, five endpoint documents and three services" | seven, six, six and five — the section headings below it are right |
| `docs/services/reference-scope.md:47` | "no tenant column in any of the **eleven** migrations" | twelve, since `V12` |
| `docs/README.md:98` | `konekt-client` — "**JVM only**, and that is upstream" | JVM, Android and two Apple targets; `konekt-client.md` itself says so |

**Neither a citation nor a count**, so no guard proposed here reaches them — and they are the two
that matter most, because they are what a reader meets first:

- `docs/research/research-architecture.md:11` — *"`konekt` is a white-label subscriber account for an
  eSIM MVNO: the operator takes the box, rebrands it, and gets a phone application and a server
  without writing a client."* This is the sentence [B-79](B-79-the-repository-calls-itself-a-box.md)
  removed from four entry points, surviving in the file `CLAUDE.md` tells a reader to open **first**.
  Line 21 of the same document still says *"There is no code yet"*.
- `charts/konekt/Chart.yaml:3` — `description: konekt — a white-label eSIM subscriber account`. The
  published chart is a shop window the reframe did not reach.
- `docs/design/design-brand-kit.md:21` prices *"the type scale, the two font faces"* in one cell as
  an application release, while `README.md:104` says the scale ships as a server response and
  `operator-boundaries.md:45` says server deploy. `B-83`'s acceptance criterion was that those two
  documents agree **with this one**; two were changed and the third was not, so the disagreement is
  now three-way. The prose at `design-brand-kit.md:35-38` is correct and the table above it is not,
  which is the arrangement `B-82` argued against — a table is read first.

## The decision

- **Fix all twelve, and add a gate for the half that has a shape.** A citation of `B-NN` sitting
  beside an unfinished marker — *is not built*, *until it closes*, *is the item*, *unfinished*, *not
  yet*, *no … yet* — is checkable against the frontmatter `status` the index already parses. That is
  a script in `make check`'s `gate`, beside `backlog_index.py` and `docs_check.py`.
- **Not every mention of a closed item.** Most citations of closed work are correct and load-bearing
  — *`B-64` is why the index exists*, *per `B-36`* — and flagging them would produce a check people
  switch off within a month, which `docs_check.py`'s own header warns about. The signal is the
  collocation, not the citation.
- **It must report what it examined.** A checker that found no citations passes silently, which is
  the failure `B-24` and `B-09` both exist for. It counts the citations it resolved and fails on
  zero.
- **Scope: the five layer directories, `docs/design/`, `README.md` and `CLAUDE.md`.** Excluded:
  `docs/backlog/`, where an item legitimately describes the state it was written in, and
  `docs/research/source-draft.md`, which is preserved verbatim by rule.
- **The rejected alternative is a periodic re-read of the documentation.** That is what has been
  happening; it found this class twice and both times after the fact.
- **The second rejected alternative is to teach `code_anchors.py` to do it.** It resolves paths, and
  every path in every one of these twelve is valid. A different question needs a different script.
- This item does **not** invent a guard for a claim that is neither a citation nor a count. The
  three above are fixed by hand and the absence of a gate for them is stated rather than papered
  over — a document that says something false about the product is caught by a reader or not at all.

## Acceptance

- AC: every row of the three tables above is corrected, and `research-architecture.md`'s opening
  paragraph describes what `README.md` describes.
- AC: `scripts/stale_citations.py` runs in `make check`'s gate and fails when a document outside the
  excluded set cites a `done` or `dropped` item beside an unfinished marker.
- AC: proved by mutation — restoring any one of the eight citations above turns the gate red, and
  the message names the file, the line and the item's actual status.
- AC: the script prints how many citations it resolved and fails if that number is zero.
- AC: the four prose counts either become correct or stop being prose — a number that a generated
  heading already carries does not need a second copy in a sentence above it.
- AC: `make check` green.

## Anchors

| What | Where |
|---|---|
| The citations | `docs/services/reference-scope.md`, `docs/services/konekt-server.md`, `docs/services/operator-boundaries.md`, `README.md` |
| The counts | `README.md`, `docs/README.md`, `docs/services/reference-scope.md` |
| The claims no gate reaches | `docs/research/research-architecture.md`, `charts/konekt/Chart.yaml`, `docs/design/design-brand-kit.md` |
| The gate | `scripts/stale_citations.py` (new), `Makefile` |
| What it reads | the item frontmatter, the way `scripts/backlog_index.py` parses it |

## What was done

All twelve corrected, plus a thirteenth the item's own reading missed and **four more the gate found
on its first run** — which is the argument for the gate, made by the gate.

**The eight citations.** Each now describes what is true: the chart refuses a second replica, the two
verticals shipped, the sweeper claims before it compensates, two of the five workers are refused
rather than one, the schema is generated and committed, the application has a name in
`AndroidManifest.xml`, and the client is not a library with nothing around it.

**The four counts stopped being prose rather than becoming right**, which is the half of the
acceptance criterion worth keeping: a number a generated heading already carries does not need a
second copy in a sentence above it, and the second copy is the one that rots. `docs/README.md` now
points at its own map instead of counting it; `reference-scope.md` says "any migration" instead of
naming eleven; `README.md` describes the stopped items instead of counting them.

**The three no gate reaches, by hand.** `research-architecture.md`'s opening paragraph was the
sentence [B-79](B-79-the-repository-calls-itself-a-box.md) removed from four entry points, surviving
in the file `CLAUDE.md` tells a reader to open first. Its *"There is no code yet"* is now marked as
preserved-as-written rather than corrected, because amending research at the point of divergence is
this format's rule and rewriting the premise would hide what the facts below were checked against.
The chart's `description` — a published shop window — was reworded. `design-brand-kit.md`'s table
split typography into scale and family, which is what the other two documents already said; its prose
below the table said something muddled about "the client side in practice" and now agrees with it.

**A thirteenth, not in the item.** `konekt-client.md` carried `JVM + iosArm64 + iosSimulatorArm64` in
its frontmatter and *"JVM and two iOS targets"* in its body, both written before
[B-85](B-85-the-client-has-no-android-target.md) added Android. The item cited `docs/README.md` for
this claim and noted that `konekt-client.md` "itself says so" — it did not.

## The gate

`scripts/stale_citations.py`, in `make check`'s **gate** rather than among the reports: unlike a
rotten anchor, none of these can be caused by a rename in somebody else's repository. Every instance
is a claim made in this tree about this tree.

It flags the **collocation** — a `B-NN` sitting in the same sentence as a phrase asserting the work
has not happened, when the item's frontmatter says `done` or `dropped`. Not the citation: most
references to closed work are correct and load-bearing.

**On its first run it found four more**, none of them in the tables above:

| Where | What it said |
|---|---|
| `docs/api/api-openapi.md:25` | "`B-24` **is the item that** turns the walk into a gate" — it did turn it into one |
| `docs/api/api-openapi.md:82` | `assertTheWalkVisitedEveryTarget` has "**no caller yet**" — `e2e TckWalkTest:95` calls it |
| `docs/api/endpoint-purchase.md:17` | "There is **no generated schema yet** (`B-23`)" |
| `docs/features/feature-tariff-change.md:149` | a **false positive**, and the useful one |

### The false positive is why the rule has an escape hatch

*"`B-86` is the item that displayed them"* is past tense and true; only the phrase `is the item`
matches. Distinguishing tense is not a regex's job, and a rule with no usable way out is a rule that
gets deleted the first time it is wrong — so a line carrying `<!-- citation-ok -->` is skipped, and
the marker stays visible in the source so the next reader can see a claim was made. That sentence was
reworded instead, because rewording was genuinely clearer; the hatch exists for the case where it
would not be.

## Verified

- **Proved by mutation, three of the eight restored one at a time.** Each turns the gate red, and the
  message names the file, the line, the item and its actual status:
  `docs/services/konekt-server.md:50: cites B-23, which is done, beside "is the item"`.
- **The vacuity guard tested, not assumed.** Run against a tree with one backlog item and no
  documents: `0 citations resolved in 0 documents`, *"no citation resolved to a known item, so
  nothing was checked"*, exit 2. A checker that finds nothing must not pass — the failure
  [B-24](B-24-tck-in-ci-with-coverage-assertion.md) and [B-09](B-09-outbox-guard.md)
  both exist for.
- **The escape hatch tested** in both directions: the same restored citation passes with the marker
  and fails without it.
- 169 citations resolved across 40 documents; `make check` green.

## What is deliberately still ungated

A claim that is neither a citation nor a count. The three fixed by hand above are caught by a reader
or not at all, and saying so is better than a guard that appears to cover them.

## Anchors

| What | Where |
|---|---|
| The gate | `scripts/stale_citations.py`, `Makefile` |
| The escape hatch | `<!-- citation-ok -->`, honoured per line |
| What it reads | the item frontmatter, the way `scripts/backlog_index.py` parses it |
