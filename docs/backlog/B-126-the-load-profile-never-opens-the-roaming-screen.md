---
id: B-126
title: "The load profile never opens the roaming screen, so a per-request path has never been measured"
status: done
priority: P2
size: S
stage: stage-m7-completeness
---

# B-126 — The load profile never opens the roaming screen

`scripts/measure/k6/screens.js` reads three screens — `home`, `plans`, `plans/tr-10gb-30d` — and
every allocation figure this repository quotes was taken under it. `/api/v1/screens/roaming` is a
fourth screen with a use case of its own, and no scenario has ever opened it: `ViewRoamingUseCase`
appears **zero** times in either profile under `bench/profile/results/konekt-screens-{50,200}` in
zavarnik.

The blind spot was found from the other side. sborka's perf-lint (0.4.0.54) reports `:server`'s
shipping code, and the one chain finding in it that runs per request rather than once per process is
`ViewRoamingUseCase.invoke` — six eager materialisations: `filter` over the catalogue, `groupBy`,
`toList`, `sortedBy`, `map`. A static rule pointed at a per-request path the measurement stand does
not exercise, which is the reverse of the usual arrangement and is worth fixing in the stand rather
than in the use case.

- **The decision and its reason.** The reading scenario opens the roaming screen too, and its setup
  buys a travel plan so the screen has content — an empty state is a cheaper screen than the one
  people read, which is the same reason `setup` already buys `home-20gb-30d`.
- **What it does not do**: change `ViewRoamingUseCase`. The arithmetic says that chain wastes a few
  hundred bytes per open against the ~85 KiB this service allocates per request, and a rewrite on
  that basis would be a change nobody measured. The point of the item is to have the number.
- **Rejected**: a scenario of its own for roaming. The screens scenario *is* the reading profile,
  and a fourth screen belongs in it; a separate one would mean two numbers for one question.

- AC: `screens.js` opens `roaming` alongside the other three, and a run at 200 rps produces a
  profile in which `io.konekt.roaming` frames are present.
- AC: the share `ViewRoamingUseCase.invoke` owns is written into this item — whatever it is. A
  finding that turns out to be worth nothing is the answer as much as one that is worth something.
- Anchors: `konekt/scripts/measure/k6/screens.js`,
  `konekt/server/src/main/kotlin/io/konekt/roaming/RoamingUseCases.kt`

## Done, 2026-09-11 — the finding is worth nothing, and now that is known

The scenario reads four screens and its setup buys `tr-10gb-30d` beside the home bundle. Run on the
measurement stand at a constant 200 rps (chart limits, 60 s warm-up, a 120 s allocation window,
image `konekt-server:b126`, 66 241 requests, checks 100 %, p95 2.17 ms):

- `ViewRoamingUseCase` appears in **88** stacks of the allocation profile. It appeared in **zero**
  before this change, in either of the two profiles ever taken of this service.
- It owns **0.150 % of all allocated bytes** — 3.7 % of what `io.konekt` owns (user code owns 4.01 %
  with the fourth screen in the mix).
- The whole roaming path together — the use case, the cards, the date arithmetic, the Exposed
  repository — owns **0.601 %**, 15 % of user code.

| owner | share of all bytes |
|---|---|
| `ViewRoamingUseCase.invoke` | 0.150 % |
| `RoamingPackageCards.of` | 0.100 % |
| `RoamingDates.on` | 0.100 % |
| the Exposed repository and the rest | 0.251 % |

**So the chain stays.** sborka's perf-lint writes rules only where a profile charged more than 2 %
of something; 0.15 % is a tenth of that line, and the collections behind it are single-digit — a
subscriber's packages and four plans in the catalogue. Rewriting it would be a change justified by a
rule rather than by a measurement, which is the thing that rule exists not to do.

**What the item was actually worth** is the other half: a per-request path that no profile had ever
touched now has a number, and the reading scenario stopped being three screens of a four-screen
product. The finding came from a static rule pointing at code the stand never ran — worth
remembering as a way to find blind spots in a harness, not only in code.
