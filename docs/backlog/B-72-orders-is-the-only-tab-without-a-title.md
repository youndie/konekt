---
id: B-72
title: "Orders is the only tab that opens with no title"
status: done
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

## What was done

**The heading, composed by the screen**, like its three siblings.

**The more interesting question, answered rather than dodged:** the shell COULD supply it — it draws
the bar and knows which tab is current — and it should not. A screen reached outside a tab would then
lose its title or grow a second one, and this product reaches the purchase result, the plan detail and
the install wizard exactly that way. Every screen naming itself is what the other three already do.

**The guard is over the set, which is the only level at which the defect exists.**
`TabScreensNameThemselvesTest` builds all four and asserts each opens with a heading that says
something. A fifth tab added without one fails there.

That shape is not incidental. A gallery frame of Orders is a perfectly good picture of Orders — every
frame is sized to its own content — so "this screen starts differently from its three siblings" is a
fact about four screens seen together and about none of them alone. The same blind spot that hid a
bottom bar landing in the middle of a window until somebody ran the application.

Home is the one conditional member and the test says so: its heading is the operator's display name,
drawn only when the brand kit carries one, because a white-label product that invented a name would
print the wrong operator's on the operator's own screen.

Proved by mutation: removing the title fails it, naming the tab and what it opens with instead.

## Anchors

| What | Where |
|---|---|
| The screen | `feature/purchase-server-data/.../HistoryScreen.kt` |
| Its three siblings | `server/src/main/kotlin/io/konekt/screens/` |
