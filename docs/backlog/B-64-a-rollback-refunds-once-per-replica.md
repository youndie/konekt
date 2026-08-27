---
id: B-64
title: "A purchase abandoned at the confirmation refunds once per running replica"
status: open
priority: P0
size: M
stage: stage-m1-money
epic: feature-buy-package
---

# B-64 — Two sweepers, one saga, two refunds

An order left at the confirmation expires and `SuspendedPetichSweeper` compensates it.
`HoldFundsInterceptor.compensate` calls `balances.release(...)`, which adds the amount back and writes
a `release` ledger entry. **Nothing claims the saga first**, so every process running a sweeper
compensates the same expired order, and the money is returned once per process.

## The evidence, read out of the stand's database

One order, one hold, two releases two milliseconds apart:

```
 kind    | amount_minor |  created_at
---------+--------------+---------------
 hold    |         -900 | 1787862781096
 release |          900 | 1787863110388
 release |          900 | 1787863110390
```

That account's whole ledger: `+9000 +1500 +9000 +1500` paid in, `-1500 -1200 -900 -1500 -1200 -900`
held, `+900 +900` released. **The subscriber ends $9 richer than they put in.**

Every account still agrees with its own ledger — checked, zero disagreements — which is the
uncomfortable half: the account and the record agree on a wrong number, so no reconciliation between
the two would ever find it.

## Why two, here and in production

The stand runs `server` and `server-declining`: the same image on the same database, differing only in
`PAYMENT_MOCK_MODE`. Both start the workers, so two sweepers poll one `petiches` table. In a
deployment the same thing is `server.replicas > 1`, which is the ordinary way to run a service.

**The chart already carries this objection about something cheaper.** `charts/konekt/templates/server.yaml`
refuses to render when `simulateTraffic` is on with more than one replica, because *"each pod runs its
own simulator, so every subscriber's allowance would drain at a multiple of the configured rate, with
nothing in any log to say so"*. The identical argument applies to the sweeper, which multiplies money
rather than usage, and nobody made it. `replicas: 1` is the default, so the product has never been run
in the shape that shows it — except on the stand, which has been running it all along.

- **The fix is in konekt's ledger and not in a lock.** A `release` for an order that already has one is
  inventing money whatever caused the second call, so the record itself should refuse it: a unique
  index on `ledger_entry (order_id, kind)` — every kind is at most one per order today — and the
  insert attempted BEFORE the balance moves, so a violation rolls the whole transaction back and the
  second compensation is a no-op. That is this repository's own idiom: *a conditional write, never a
  read-then-write*, which the session family already follows for the same reason.
- **The rejected alternative** is claiming the saga in the sweeper. It is petich's to do — worth an
  upstream ask — and it is not enough on its own: it makes the race rarer, and the ledger is where the
  invariant actually lives.
- **The migration is the careful part.** A unique index cannot be created on a table that already
  holds duplicates, so a deployment that has already double-refunded fails the migration — which is
  the right signal and still a failed deploy. It also needs the concurrent-index recipe this
  repository documents: `executeInTransaction=false` in the `.conf` beside it, and the lock settings
  `DatabaseFactory` already applies.
- **Not covered:** the same shape for `credit`/`top_up_reversal`. The top-up saga never suspends, so
  no sweeper reaches it — but the index covers it for free and the guard should be symmetric.

- AC: two compensations of one order leave one `release` and one refund.
- AC: proved by running the compensation twice against a real Postgres, not by reasoning about the
  sweeper.
- AC: the history stops showing the order twice — the duplicate row is the join multiplying on a
  one-to-many that should be one-to-one, and it disappears with the cause rather than being papered
  over in the query.
- Anchors:
  `feature/purchase-server-data/src/main/kotlin/io/konekt/feature/purchase/server/data/ExposedPurchaseRepositories.kt`,
  `feature/purchase-server-domain/src/main/kotlin/io/konekt/feature/purchase/server/domain/PurchaseInterceptors.kt`,
  `charts/konekt/templates/server.yaml`, `shared/db/src/main/resources/db/migration/`.

Found by a from-scratch re-check of the running stand rather than by a test: every suite was green,
and both the account and the ledger agreed with each other about the wrong amount.
