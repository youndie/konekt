---
id: B-64
title: "A purchase abandoned at the confirmation refunds once per running replica"
status: done
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

## What landed

**The invariant is in the ledger.** `V11` adds a unique index on `ledger_entry (order_id, kind)` —
every kind is at most one per order — and `release`, `credit` and the top-up reversal now write the
ENTRY BEFORE the balance moves. A second attempt violates the index, the exception rolls the whole
transaction back, and the balance is never touched. The order of those two statements is the fix:
written the other way round the balance would move and be rolled back too, which is the same outcome
by luck rather than by construction.

A violation is swallowed rather than rethrown — a compensation that runs again has nothing to do, and
that is not a failure to report. Caught by SQLState `23505` and not by message: anything else is
rethrown, because swallowing a broken connection is how a balance stops moving with nothing in a log.

**Proved against a real Postgres, four ways.** Two compensations in sequence; two racing on
`Dispatchers.IO`, because a read-then-write guard passes the first and fails the second; a positive
control that a FIRST compensation still returns the money — without it, a `release` that did nothing
at all would satisfy the rest, and money held and never returned is worse than money returned twice;
and two different orders each getting their own refund, which is why the index is per order.
Dropping `UNIQUE` from the migration fails the two that matter and leaves both controls green.

## Two things the migration comment promised and did not know

The first draft said the index would refuse a database that had already double-refunded. True, and
measured rather than left as a claim: a duplicate pair was inserted and the build answered
`could not create unique index … Key (order_id, kind)=(…, release) is duplicated`.

What it did not say is that **a failed `CONCURRENTLY` build leaves an INVALID index behind** —
`indisvalid = f` — so the next deploy stops on "relation already exists" rather than on the
duplicates, and a runbook that only reconciles the rows still cannot get past it. The recovery is
`DROP INDEX IF EXISTS` first. Both facts are now in the file, with the queries.

## And two guard defects, found by being the first to use a documented recipe

Nothing had ever used `CONCURRENTLY`, so the sidecar convention this repository documents had never
been exercised. The first one broke two checks at once:

- `MigrationFilesTest` read `V11__….sql.conf` as a migration — "not a Flyway versioned migration",
  and then as a second file at version 11. It excludes sidecars by suffix now, and by suffix rather
  than by pattern so that a file which is neither still fails the naming check.
- `ExpandAndContractTest` **required a property Flyway refuses.** It demanded
  `postgresql.transactional.lock=false` in the script config; that is a CONFIGURATION property, and a
  run whose sidecar carries it dies with "Unknown configuration property". `DatabaseFactory` sets it
  once, which is what `CLAUDE.md` says and what the guard contradicted.

A recipe that is documented, guarded and never run is a recipe nobody has checked.

## Still open, and deliberately

The sweeper still does not claim a saga — two of them still both compensate, and the second one now
does nothing. Claiming it is petich's to do and worth an upstream ask; it would make the race rarer
and the ledger is where the invariant belongs. What is closed here is the outcome.
