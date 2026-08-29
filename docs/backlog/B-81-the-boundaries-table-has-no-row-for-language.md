---
id: B-81
title: "The boundaries table has no row for language, currency, date format, time zone or the app's own name"
status: open
priority: P1
size: S
stage: stage-m6-reframe
---

# B-81 — The rows a reader will look for first are the six that are missing

[`operator-boundaries.md`](../services/operator-boundaries.md) carries fourteen rows and opens with
the right principle: *a product whose boundaries are discovered by the reader is a support contract
rather than a box*. Six axes anybody evaluating a white-label build asks about on the first day are
not among them, and all six were established by reading the code:

| Missing axis | What the code says | Cost |
|---|---|---|
| Language | No `stringResource`, no `Accept-Language`, no bundles anywhere. Every string is an English literal in the server's Kotlin — `HomeScreen.kt`, `PlansScreen.kt`, `RoamingZoneNames.kt` | server deploy, and there is no mechanism for two languages at once |
| Currency | `MoneyFormat` carries its own five-currency layout table and says so: *the product has one audience per deployment* | server deploy |
| Date format | `DayFormat` pins `Locale.ENGLISH` and `"d MMM"` | server deploy |
| Time zone | `DayFormat` pins `ZoneId.of("UTC")`; `B-33` records the billing boundary living in one fixed zone | server deploy |
| The app's name and icon | There is no application to name — see [B-85](B-85-the-client-has-no-android-target.md) and [B-90](B-90-the-ios-build-cannot-leave-the-simulator.md). `scripts/ios-home-app.sh` writes `CFBundleName` by hand | client release, once there is a client to release |
| Icons in the interface | kompot has no icon vocabulary — no wire type, no token. The bottom bar is text labels, and `BottomNavRenderer` says so | not available at any price today |

The last row is the one that matters most, because it is the only cost the table currently has no
column for: **an axis that cannot be changed at all**. Fourteen rows that each name a price imply
that everything has one.

- **The decision: add the six rows, and add a fifth cost — `not available` — for the axis the wire
  has no vocabulary for.** A price list that silently omits the items not for sale is read as a
  complete price list.
- The rejected alternative is a paragraph under the table. The document's own opening sentence
  explains why it is a table: *a paragraph lets the awkward rows hide*.
- This item does **not** implement localisation or an icon vocabulary. Both are non-goals and belong
  in [B-80](B-80-the-non-goals-are-nowhere.md); this one only stops the table from implying they are
  cheap.

- AC: the table has rows for language, currency, date format, time zone, app name/icon and interface
  icons, each with a cost and the file or research section that establishes it.
- AC: the four costs become five, with `not available` defined in the same table at the top.
- Anchors: `docs/services/operator-boundaries.md`,
  `shared/server-common/src/main/kotlin/io/konekt/money/MoneyFormat.kt`,
  `shared/server-common/src/main/kotlin/io/konekt/money/DayFormat.kt`,
  `server/src/main/kotlin/io/konekt/roaming/RoamingZoneNames.kt`,
  `client/src/commonMain/kotlin/io/konekt/client/render/BottomNavRenderer.kt`.
