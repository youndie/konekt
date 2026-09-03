# The first soak, and why its twelve hours are not a measurement

Started 2026-09-02T14:26:19Z on the spare box, `ghcr.io/youndie/konekt-server:v0.1.40` under the
chart's limits, `DURATION=12h READ_RATE=3 BUY_PER_MINUTE=2 SUBSCRIBERS=20`, the sampler every 60 s.
It ran the full twelve hours and its unit ended `success`. **It measured fifteen minutes of the
product and eleven hours forty-five of the 401 path.**

`k6-summary.json` is where that is legible and nowhere else:

| | passes | fails |
|---|---|---|
| `setup / top-up 201` | 20 | 0 |
| `home 200` | 642 | 31 682 |
| `plans 200` | 680 | 31 645 |
| `orders 200` | 683 | 31 684 |
| `profile 200` | 691 | 31 894 |
| `top-up 201` | 30 | 1 411 |

2 696 successful screen reads at 3/s is **15.0 minutes**, and `JwtSessions.accessTtl` is
`15.minutes`. The scenario signed twenty subscribers in inside `setup()`, returned their access
tokens as setup data — which k6 freezes for the whole run — and every VU held those strings until
the run ended. The arithmetic is the whole diagnosis.

**Nothing said so, and that is the second half of it.** k6's progress line ends `reading ✓ [100%]`,
which is the scenario reaching its duration, not its checks passing; `soak.js` declared no
thresholds, so k6 exited 0; `--collect` had removed the unit, so `systemctl show` answered from a
fresh object and said `success`. Three things that look like a verdict and are not, in a row.

## What the record here is good for

`samples.csv` is the sampler's own file and its columns are sound up to the last one — server CPU,
memory and descriptors, Postgres memory and connections, broker memory and disk, and the three
row counts. Over the twelve hours the server went 143 → 221 MiB and held 155 → 166 descriptors on
11 Postgres connections, which is a process that did not leak **while answering 401s**, and is
worth exactly that much.

Two defects in the file itself, both from the box running a copy of `sample.sh` taken minutes
before `5191697` improved it:

- **the header names thirteen columns and every row carries twelve.** The old sampler collected the
  GC pause and the GC line count in one `docker exec`; the pause pattern matched nothing, so the
  field collapsed and the single value present is the LINE COUNT. Read by column name it is
  `gc_last_pause_ms`, it grows 29 → 1 207 monotonically, and it is not a pause. There is no pause
  data in this run;
- **no `postgres_cpu_pct` and no `broker_cpu_pct`** — those columns were added an hour into the run.

The rows after 2026-09-03T02:26Z are the sampler still running against an idle stand; the soak
itself is the first 648.

## The re-run

`B-117`'s measurement 2, restarted 2026-09-03T21:25:38Z with the harness fixed —
`tokenFor` re-signs a subscriber in before the token's fifteen minutes are up, and `soak.js`
declares `checks: ['rate>0.95']`, which was proved by mutation to exit 99. Its record will land
beside this one.
