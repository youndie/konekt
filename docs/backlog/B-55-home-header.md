---
id: B-55
title: "The home screen has no header, and the two things a header names are not in the domain"
status: done
priority: P2
size: S
stage: stage-m3-product
epic: feature-client-shell
---

# B-55 — A header needs a brand name and a subscriber name, and this build has neither

Section 01 opens with "Konekt" at 800/22 and two 40px circles on the right, the second carrying the
initials "AK". Section 05 names the subscriber outright: "Anna Kotova". The served home tree starts
at the balance.

Both halves are missing data rather than missing layout:

- **The brand name.** `BrandTheme` carries colours and typography — kompot's theme vocabulary is
  `ColorToken` and `TypographyToken` and nothing else (research-architecture §1.2). A deployment's
  NAME is not on the wire at all, and this is a white-label product where hard-coding "Konekt" in a
  screen would be exactly the thing the product exists to disprove.
- **The subscriber's name.** `subscriber` holds an msisdn. Nothing ever asks for a name, so initials
  would have to be invented — and a screen that draws invented initials is a mockup wearing the
  product's clothes.

- **The question this item is, rather than the task.** Either the brand kit grows a display name
  (a field on the served theme, small and honest), or the header carries no brand name and the canvas
  records that. The same for the avatar: either sign-up collects a name, or the circle holds the
  number's last two digits, or there is no circle. **A difference is only a defect once somebody has
  decided which of the two moves.**
- What is NOT in question: nothing here gets faked to make a screenshot match.
- AC: either the tree carries a header whose every string came from data, or
  `docs/design/design-app-canvas.md` records that this build serves no header and why.
- Anchors: `server/src/main/kotlin/io/konekt/screens/HomeScreen.kt`,
  `feature/theme-shared-api/`, `docs/design/design-app-canvas.md`.

## What landed

**The brand kit gained a `displayName`,** which is the option taken: it is a fact about the
deployment, it lives in the one file an operator already edits, and it needed no wire type of its own
because the server builds the screen. `KompotTheme` is the toolkit's and was not touched — the kit is
served as bytes, so the field travels harmlessly to a client that ignores unknown keys, and no
upstream ask was needed.

**Null draws no header.** A white-label product that guessed a name would print the wrong operator's
name on the operator's own screen, which is worse than printing none.

**The avatar is refused, not deferred.** `subscriber` holds an msisdn and nothing else, so initials
would have to be invented. It joins the day sign-up asks for a name.

**Binding it found a gap in a guard.** The catalogue was constructed inline inside `routing`, so a
route injecting it resolved to nothing — `RoutesResolveWhatTheyInjectTest` said so immediately, which
is what it is for. It is a named `brandModule(catalogue)` now, so both the application and the test's
module list call the same function rather than keeping two copies of one graph.

**One limit on the goldens, named rather than left to be found:** brand B's frames are recorded off a
brand-a deployment, so they are drawn in the ink palette and still say "Konekt". The claim those
frames carry is about markup and palette; a served name is content.
