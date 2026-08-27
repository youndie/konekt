---
id: B-55
title: "The home screen has no header, and the two things a header names are not in the domain"
status: question
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
