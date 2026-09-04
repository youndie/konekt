# The soak that measured the product

Twelve hours, 2026-09-03T21:25:38Z → 2026-09-04T09:25:38Z, on the spare box:
`ghcr.io/youndie/konekt-server:v0.1.40` under the chart's limits (one CPU, 1 GiB) beside Postgres 18,
booblik and the three collectors, `DURATION=12h READ_RATE=3 BUY_PER_MINUTE=2 SUBSCRIBERS=20`, the
sampler every 60 s. The stand was reset to an empty database and an empty broker before it started.

It replaces the run of 2026-09-02, which held one fifteen-minute token for twelve hours and measured
the 401 path — [that record](../soak/README.md) is kept because the harness defects it exposed are
the reason this one is trustworthy.

## What k6 says, which is the authority

| | |
|---|---|
| checks | **131 061 passed, 0 failed** |
| requests | 170 083, **0 failed**, 3.94/s |
| iterations | 131 041, 3.03/s, 0 interrupted |
| purchases completed | **1 440** — exactly 2/min × 720 min, none refused |
| latency | median 4.08 ms, p90 7.86, p95 9.93, max 362 |

The threshold this scenario gained after the void run — `checks: ['rate>0.95']` — was not crossed.
The same 12 hours produced 131 041 iterations both times; only the auth differed, which is what makes
the two runs comparable and the first one worthless.

## What the sampler says, over the soak's own window

647 samples. The slope is a least-squares fit over the twelve hours, per hour:

| series | first | last | median | per hour |
|---|---|---|---|---|
| server CPU % | 6.3 | 6.5 | 2.1 | −0.11 |
| **server memory MiB** | 185.8 | 248.2 | 225.4 | **+3.87** |
| server file descriptors | 167 | 170 | 170 | +0.26 |
| Postgres CPU % | 0.8 | 5.5 | 3.9 | +0.01 |
| Postgres memory MiB | 68.9 | 77.9 | 73.0 | +0.96 |
| Postgres connections | 11 | 11 | 11 | 0 |
| broker CPU % | 0.19 | 0.18 | 0.16 | −0.08 |
| **broker memory MiB** | 58.7 | 140.8 | 121.5 | **+6.61** |
| **broker disk MiB** | 1 | 69 | 51 | **+5.93** |
| **outbox rows** | 114 | 2 986 | 1 550 | **+240** |
| ledger rows | 171 | 4 479 | 2 325 | +360 |
| usage_counter rows | 135 | 135 | 135 | 0 |
| GC last pause ms | 2.4 | 10.7 | 2.4 | +0.05 |
| GC collections | 78 | 5 894 | 2 998 | +485 |

**Three of these are flat and that is the result.** File descriptors moved by three in twelve hours
and Postgres connections not at all, so the pool and the sockets are not leaking; server CPU sits at
a 2% median with the setup burst as its maximum. Latency did not drift: the p95 above is for the
whole run, not for its first hour.

**The ledger's rate is arithmetic, and it agreeing is the check that the load was real.** 120
purchases an hour, three entries each — hold, capture, the top-up that funded it — is 360 rows an
hour, and the fit says 360.001.

**`usage_counter` is flat because it is a row per subscriber, not per event.** The count says nothing
about whether usage was applied; a future soak wanting that answer must sample a SUM, not a COUNT.
It is left here as measured, and named as uninformative, rather than quietly dropped.

## The three that rose and did not level off

- **Server memory, +3.9 MiB/h — 62 MiB over the run, ending at 248 of 1 024.** Twelve hours is not
  long enough to tell a heap settling toward its ceiling from a slow leak, and this report will not
  pretend otherwise. What can be said: it never fell, the GC ran 5 894 times, and the pause grew from
  2.4 ms to a 10.7 ms last-sample with a 16 ms maximum. The question wants a longer run or a heap
  dump, not a bolder sentence.
- **Broker memory, +6.6 MiB/h, and broker disk, +5.9 MiB/h.** The disk is by design and the arithmetic
  says so: retention drops whole 32 MiB segments and never the active one, so at 6 MiB/h the first
  segment closes around hour five and the 128 MiB bound is not reached until roughly hour twenty-one
  — past this window. A soak that wanted to watch retention actually delete something needs a day.
- **The outbox only grows: +240 rows/h.** `outbox_events` carries a `status` and petich's relay marks
  rows rather than removing them, so nothing prunes it. At this load that is 2.1M rows a year, on a
  table whose index is `(status, created_at)`. Not a defect of this build — a property of the
  library's shape, measured, and worth a decision rather than a discovery.

## Files

`k6-summary.json` is k6's own summary — the only artefact of the run that says whether it passed.
`samples.csv` is the sampler's, with ten samples past the end where the sampler outlived the soak;
the window above is the first 647.
