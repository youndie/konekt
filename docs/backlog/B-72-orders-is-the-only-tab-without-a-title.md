---
id: B-72
title: "Orders is the only tab that opens with no title"
status: open
priority: P3
size: XS
stage: stage-m4-proof
epic: feature-client-shell
---

# B-72 — Three tabs name themselves and the fourth does not

Home opens with **Konekt**, Plans with **Plans**, Profile with **Profile**. Orders opens with the
filter chips flush against the top edge and no heading at all.

Small, and it is the kind of thing a screenshot gallery cannot report: each frame is sized to its own
content, so "this screen starts differently from its three siblings" is only visible when the four
are looked at in sequence in the same frame.

## Fix

Give the history screen the same heading its siblings have. Whether the shell should supply it for
every tab rather than each screen doing so is the more interesting question, and the reason this is
worth a line rather than a silent patch.

## Anchors

| What | Where |
|---|---|
| The screen | `feature/purchase-server-data/.../HistoryScreen.kt` |
| Its three siblings | `server/src/main/kotlin/io/konekt/screens/` |
