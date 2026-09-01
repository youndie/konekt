---
id: B-110
title: "The four tabs are words with no icons, because kompot has no icon vocabulary"
status: done
priority: P2
size: M
stage: stage-m7-completeness
---

# B-110 — Home, Plans, Orders, Profile — and nothing above them

The canvas draws an icon over each tab. This build draws four labels, and `BottomNavRenderer` says
why in as many words:

> LABELS AND NO ICONS, because kompot has no icon vocabulary — no wire type, no token — and an icon
> name here would be a string this client maps to a drawable it compiled in, which is a second
> dictionary kept in step by hand.

That reasoning is still right, and it is the reason this is an **M** rather than an **S**: the cheap
version is exactly the thing the comment refuses.

## Why the obvious fix is the wrong one

Adding `icon: String` to `BottomNavItem` and a `when (icon)` in the renderer gives the server a
vocabulary the client must already know. It is the same shape as `konektActionWireNames` and the
component dictionary — two lists that must agree, with nothing holding them together — except worse,
because the failure is silent: an unknown name draws nothing, and a tab with no icon looks like a tab
whose icon has not loaded.

Whatever is built here needs the property the rest of the wire has: **a name the client does not know
must fail loudly, in a test, before a deployment draws it.**

## The three shapes, and none is free

| | What it costs | What it buys |
|---|---|---|
| A closed enum on the wire, generated into the client like the components are | a client release per icon | the compiler holds both sides together |
| A name plus a client-side table, guarded by a test that the table covers the enum | a client release per icon, plus a guard | the same, with more moving parts |
| The icon as **data** — an SVG or a vector path on the wire | no client release at all | a renderer that draws arbitrary vectors, and a server that owns the drawing |

The third is the only one that makes an icon a server decision, and it is also the only one that
changes what kompot is for. It deserves the upstream conversation the comment already points at
rather than a decision taken here on a Tuesday.

## What was chosen, and why the canvas decided it

**The third shape: the icon travels as vector path data.**

The choice was not made on taste. The canvas's tab icons are **not Material's glyphs** — they are
stroked line art on a 24-unit grid, drawn as SVG paths, and there are 59 SVGs across the design. A
closed enum over `Icons.Default` would have drawn *different pictures* than the design asks for, which
is not a compromise but a different product. That eliminated both cheap options at once and left a
choice between compiling 59 assets into the client and sending the shape.

konekt already sends a shape: `EsimQrComponent` carries a payload and the client draws the modules on
a `Canvas`. An icon is the same arrangement with a shorter argument.

**The colour does not travel.** The canvas writes `stroke="#5A6663"` on each of these and `VectorIcon`
has no such field: the client strokes the shape in the role the bar asks for, so a brand kit can
repaint icons it has never seen. A server-sent hex would have been the one thing a rebrand could not
reach.

**`PathParser` is Compose's own**, not a reader written here. SVG path grammar has arcs, implicit
repeats and relative commands; a hand-rolled parser would draw *something*, and a wrong picture and a
right one are both pictures.

## The thing this nearly shipped without

Icons were added, every golden was re-recorded, and **not one pixel moved.**

The screenshot fixtures — `client/src/jvmTest/resources/recorded/*.json` — are committed responses
written by hand, and nothing held them to the server. `RecordedScreenIsRealTest` checks they decode;
nothing checked they were still what this server sends. So the suite whose entire job is to show what
the application looks like was showing what it used to look like, silently, and would have gone on
doing so for every wire change after this one.

`RecordedBarMatchesTheServerTest` closes it for the bar — the one piece that depends on nothing but
`Shell` and appears in every frame — and its failure message prints the JSON to paste in, so
re-recording is mechanical rather than a transcription.

## The copy that had to stay honest

`Shell.TabIcons` is a transcription of four `<svg>`s in `docs/design/konekt-esim-app.dc.html`, and a
transcription drifts in both directions: somebody edits the constant, or the design moves. Neither
shows up as an error — it shows up as an icon that is slightly not the one that was drawn, which is
the kind of wrong nobody reports.

`TabIconsMatchTheCanvasTest` reads the canvas, which is in the repository, and compares. It also
checks the other direction — nothing sent that the design does not ask for — with the two arcs that
stand in for its `<circle>` elements listed by name, so the exemption cannot grow to cover a shape
somebody made up.

Writing it cost one real bug in the guard itself: the obvious regex, `<svg…</svg>` followed by the
label, matches from the EARLIEST `<svg` that can still reach it — the previous tab's. It compared
Plans against the house and Profile against the document, and both mismatches looked exactly like a
design that had moved. It walks backwards from the label now.

## Proved by mutation

| Mutation | Result |
|---|---|
| an icon's path data replaced with nonsense | `every icon on the wire parses into something with a shape` FAILED |
| the server changes a path and the fixtures do not follow | `every recorded bar is the bar this server would send` FAILED |
| `icon = tab.icon` dropped from the bar | the same guard FAILED |
| a path edited one unit away from the design | both `TabIconsMatchTheCanvasTest` assertions FAILED |
| a shape added that the canvas never draws | `nothing is drawn that the canvas does not ask for` FAILED |

Three guards, and none is worth much alone: the recordings are still the server's, the client can draw
what is in them, and what is in them is what the design asked for.

## What the build's own guards asked for on the way

Four of them fired, and each was right:

- `CitedTestsExistTest` — two comments named tests that did not exist yet. One was renamed to the
  test that does; the other was a promise, and the promise was kept rather than deleted.
- `KonektSchemaGoldenTest` — `VectorIcon` is on the wire, so the committed component schema had to
  move with it.
- `ScreenshotCasesTest` — no new frame here, but it is the guard that made `B-109`'s new one explicit.
- `ktlint` against `-Werror` — the two disagree about `else -> Unit`; an empty block is what both
  accept, and the reason is written where the next person meets it.

## Acceptance criteria

- AC: the choice among the three is written down with its reason before anything is built, and
  `operator-boundaries.md` gains the row for whichever it is. **Done, and the price went the other
  way:** an icon is a *server deploy*, which is why the row says so explicitly.
- AC: a name the client cannot draw fails a test rather than drawing nothing. **Held in the new
  shape:** there is no name, and unreadable path data is what fails.
- AC: whatever lands is exercised by a screenshot, not only by a tree assertion. **Twenty-three
  goldens moved and were read** — and getting them to move at all is the story above.
- AC: if the answer is an upstream ask against kompot, the issue is filed there and this item cites
  it. **Not filed, and the reason is that the answer stopped being an ask.** kompot's standard
  vocabulary has no icon and no image type — checked in the published artefact — but nothing in this
  solution needs one: `VectorIcon` is konekt's own field on konekt's own component, drawn with
  Compose's parser. What could still be worth proposing upstream is an `icon` COMPONENT so a shape
  can appear anywhere rather than only on a bar, and that is worth proposing after this build has
  used it in a second place — one use is not a pattern.

## Anchors

| What | Where |
|---|---|
| The bar | `shared/components/src/commonMain/kotlin/io/konekt/components/BottomNavComponent.kt` |
| The renderer, and the reasoning as it stands | `client/src/commonMain/kotlin/io/konekt/client/render/BottomNavRenderer.kt` |
| Who decides the tabs | `server/src/main/kotlin/io/konekt/screens/Shell.kt` |
| What a wire change costs | [operator-boundaries](../services/operator-boundaries.md) |
