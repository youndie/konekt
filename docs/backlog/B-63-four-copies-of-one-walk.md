---
id: B-63
title: "Five hand-kept lists of which components nest, and each goes stale by looking at less"
status: done
priority: P1
size: S
stage: stage-m4-proof
epic: feature-client
---

# B-63 — A walk that misses a container does not fail; it reports absence

`KompotComponent` declares no `children`. Nesting is a convention of each type — `ColumnComponent`
has `children`, `PaginatedListComponent` has `initialItems`, `BottomNavComponent` has `items` of a
holder that is not a component at all — so every test that walks a tree keeps its own list of which
types to descend into. There are four:

| Walk | Module | Knows about |
|---|---|---|
| `PurchaseScenarioTest.walk` | `:e2e` | column, row, paginated_list, surface |
| `HomeScreenTest.descendants` | `:server` | column, row, surface |
| `RecordedScreenIsRealTest.walk` | `:client` | column, row, surface |
| `EveryScreenIsReachableTest` | `:client` | none — it reads the JSON instead |

**Adding a container breaks all of them silently.** Not by failing: by looking at less. Every one of
these reports an ABSENCE — "no balance label", "no formatted amount in the recording", "the balance
did not come back" — about a tree that has the thing, one level below where the walk stopped. The
accusation lands on the product.

It has now happened four times: `paginated_list` when the history screen was built (recorded in the
e2e helper's own comment), and three at once the day `surface` arrived. The fourth walk escaped only
because it had already been rewritten to read the JSON after being caught missing `bottom_nav.items`.

- **The decision and its reason.** One walk, in `:shared:components` — the module that owns the
  dictionary is the one that knows which of its types nest, and it is already on every consumer's
  classpath. Copies exist because the walk grew where it was first needed, not because the three
  differ.
- **A shared walk is still a hand-kept list, so it needs a guard.** `KonektTreeTest` builds a tree
  from the dictionary specimens; the guard is that nesting EVERY specimen inside every container and
  walking the result reaches all of them. Then a container added without a walk entry fails at the
  place the walk lives, instead of somewhere downstream reporting that a screen is missing text.
- **Reading the JSON is the other answer and is not general.** It works for `EveryScreenIsReachableTest`
  because that one asks about a wire-level fact — an object whose `type` is `navigate`. A test
  asserting on a decoded `TextComponent.text` wants the decoded tree.
- **Not covered:** kompot's own containers. A `column` gaining a second child field upstream breaks
  this the same way, and the guard above would not see it — it walks konekt's dictionary.
- AC: adding a container component to `:shared:components` without teaching the walk fails a test in
  `:shared:components`.
- AC: the three copies are gone, and the tests that used them read the same walk.
- Anchors: `shared/components/src/commonTest/kotlin/io/konekt/components/KonektTreeTest.kt`,
  `e2e/src/test/kotlin/io/konekt/e2e/PurchaseScenarioTest.kt`,
  `server/src/test/kotlin/io/konekt/screens/HomeScreenTest.kt`,
  `client/src/jvmTest/kotlin/io/konekt/screenshots/RecordedScreenIsRealTest.kt`.

Found while closing [B-52](B-52-the-balance-is-not-a-card.md), which added the container that broke
three of the four at once.

## What landed

`konektWalk`, in `:shared:components/commonMain` beside `konektWireNames` and for the reason that
list gives: which of these types nest is a fact about the wire vocabulary, and a fact about the
vocabulary belongs where the vocabulary is — reachable from every module without a fixtures artefact
per platform.

**There were five copies, not four.** The fifth turned up in `ForwardCompatScreenTest`, and its
comment is this item written by somebody who had just been bitten and kept the copy anyway: *"a
walker that stopped at the column would have counted the nested one as missing"*.

**The guard's oracle is the JSON.** `WalkCoversEveryContainerTest` builds a tree with every dictionary
specimen inside every container, two deep, encodes it, and asserts the typed walk reaches the same ids
the serialized form does. A serialized tree cannot hide a nesting, so a container added without a
`when` branch fails beside the walk instead of downstream reporting a screen as empty.

**Keyed on `id` and not on `type`**, which was the first attempt and is wrong: an action carries a
`type` and so does a modifier, and neither is a node — keying on it would demand the walk descend into
things that are not components. Every `KompotComponent` declares `id`; nothing else in a tree does.

**Proved to bite, twice.** Removing the `surface` branch fails it; so does dropping
`paginated_list`'s `emptyState` — a field **three of the five copies never followed**, and the one
that matters most, since an empty list is exactly when the empty state is the only thing on screen.

**One boundary moved and is recorded rather than quietly crossed.** `kompot-standard` was a test-only
dependency of `:shared:components`, justified by "konekt's own components never embed a toolkit
component". `surface` made konekt a composer, and the walk has to know both halves — so the
dependency is in `commonMain` now, and the build file says what changed and why.
