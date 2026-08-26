---
id: B-21
title: "Changing tariff, as a saga with a confirmation"
status: done
priority: P2
size: M
stage: stage-m3-product
epic: feature-tariff
blocked_by: [B-08]
---

# B-21 — Changing tariff, as a saga with a confirmation

A tariff change moves money, changes quotas and takes effect on a boundary. It is the second saga, and
its value is that it reuses the first one's machinery without reusing its code — including the
suspend, because a tariff change is exactly the sort of thing a subscriber should confirm.

- **The decision and its reason.** The change takes effect at the next billing boundary rather than
  immediately, and the screen says which date. An immediate change makes proration the centre of the
  feature, and proration is arithmetic this build has nothing to say about.
- The rejected alternative, immediate effect with proration, is more realistic and buys a week of
  edge cases in a mock billing system.
- Not covered: downgrade restrictions. Any tariff can be chosen from any tariff.

- AC: a confirmed change shows the new tariff with its effective date and the old one still current.
- AC: an unconfirmed change past its TTL leaves the current tariff untouched.
- Anchors: `server/src/main/kotlin/io/konekt/tariff/`.

Background: [research-architecture](../research/research-architecture.md) §1.7.

## What landed

The second saga with a confirmation — three steps rather than the purchase's four, because nothing is
held. A tariff change moves no money until the boundary; what it holds is a **promise**, and the
compensation is withdrawing it.

- AC MET: a confirmed change shows the new tariff with its effective date **and the old one still
  current**. Both are true at once and the response says both.
- AC MET: an unconfirmed change past its TTL leaves the current tariff untouched — the sweeper
  cancels it, and a cancelled row can never become the answer to "what are they on".

Two rules the item did not ask for and the shape demanded. **One pending change at a time**: two
would race for the same boundary and the later would win by accident of ordering, leaving a subscriber
who asked twice with no way to know which they got. And **the tariff you are already on is not a
change**, which is a `Reject` rather than a no-op so the screen can say why.

## The boundary did not bind, and this feature's own test is what said so

`currentTariffId` first read the newest APPLIED row and nothing else. So a confirmed change became the
current tariff **the moment it was confirmed** — which is precisely what "takes effect at the next
billing boundary" is not, and the whole reason this item rejects immediate effect.

The date filter is the feature: `effective_at <= now`. Proved by taking it off and watching
`expected: <tr-basic> but was: <tr-max>`.

## Decisions worth not re-litigating

**A log rather than a column.** "Which tariff is this subscriber on" could be a column on `subscriber`
updated in place; then "since when" and "what before" are gone, and a change awaiting confirmation has
nowhere to sit that is not also the current answer. Appending makes both free and matches how money is
already recorded here. It also means a subscriber who never changed has no rows, so the migration
needed no backfill — which is what keeps it an expand.

**`java.time`, not arithmetic over day counts.** The first version of `BillingBoundary` walked days
and counted months by hand. Correct, and a calendar implementation nobody asked for living beside one
the JDK already ships — `:server` is JVM by construction, so there was nothing multiplatform to
preserve.

**The zone is the operator's**, the same constant `DayFormat` uses and for the reason it states: this
product does not know where a subscriber is. A boundary computed in one zone and printed in another
would be a date off by a day for half the world.
