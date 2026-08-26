---
id: B-35
title: "An end-to-end stand on docker-compose, driven by one command in both places"
status: done
priority: P0
size: M
stage: stage-m2-live
blocked_by: [B-14, B-15]
---

# B-35 — An end-to-end stand on docker-compose, driven by one command in both places

The scenario this build exists to show crosses five processes: a kompot form, a petich saga, the
outbox, the booblik broker, and back to an open SSE stream. Every test below this level can pass
while that chain is broken at a seam, because each of them owns one end of it. The stand is the only
thing that owns the whole.

- **The decision and its reason.** `deploy/compose.yaml` brings up Postgres, the broker, the server
  and the three observability binaries, and the suite drives it over HTTP with the same command
  locally and in CI. A stand only CI knows how to start is a stand nobody debugs, and the failures
  worth catching here are the ones that only appear between processes.
- **`depends_on` uses `condition: service_healthy`, and the healthcheck asks the process a question.**
  A TCP check passes against a hung process — the kernel accepts into the backlog with no help from
  it — which turns the stand's own startup into a source of false green.
- Topics are declared to the broker at startup (`BOOBLIK_TOPICS: orders:1,usage:1,notifications:1`),
  because booblik fixes its topic set then and never again. Host ports are overridable through
  environment variables; 8080 is the most contested port there is.
- The rejected alternative is an in-process end-to-end test with the broker embedded. It is faster
  and it stops proving the thing the stand exists for, since packaging and networking are two of the
  seams.
- **The suite stays small on purpose**: the happy path, the compensated path, and one live update.
  End-to-end is the slowest and most fragile layer of any suite, and a large one gets muted.
- Not covered: the mobile clients. The stand drives HTTP; the client is covered by screenshots
  (`B-28`) and by the client conformance corpus.

- AC OK: `make stand-up && make e2e` runs the scenarios green from a clean checkout — four of them:
  the confirmed purchase with the allowance landing on the home screen, the refused one rolled back
  and stated in money, the order reaching its history, and a counter moving through a real broker onto
  an already-open SSE stream.
- AC OK: an expired wait says what the stand looks like rather than that it waited. `Stand.awaitOrExplain`
  asks `docker compose ps` before giving up and names anything not running — the broker publishes no
  port and cannot be probed directly, and it is the process whose death is quietest.
- AC OK: the same two commands run in CI, in a job of their own that asks for Docker before Gradle
  does, and prints the stand's logs on failure. A stand whose logs are not in the job that failed
  sends somebody to reproduce it locally.
- AC OK, and it was pending on a premise that expired: the stand runs the **previous release's image**
  against the new schema. Nothing is tagged yet, and the second half of the old reason — that two
  commits would compare identical schemas — stopped being true when `V10__roaming_package.sql` landed.
  A commit before it has a server built against V9, and this stand runs V10. So the check is real now,
  with a commit standing in for a tag, and it becomes the tag's business the day there is one.

  `make rolling-check PREVIOUS=<ref>` extracts that commit with `git archive`, builds its server,
  starts it beside the current stand on the schema the CURRENT tree migrated, and drives the product
  through it: a sign-in and a whole purchase saga. `konekt.stand.server` points at the OLD server, so
  every helper in `Stand` drives it without knowing which of the two it is talking to.

  **It refuses to be vacuous.** With no ref and no tag it says so and stops; with a ref whose
  migrations are identical to HEAD's it stops too, because a green run of the same code against the
  same schema is a claim about rolling deploys backed by nothing.

**FOUR DEFECTS, EVERY ONE OF THEM FATAL TO THE RUNNING SERVER, found the first time this stand came
up — with 191 unit and integration tests green.** They are listed because the pattern matters more
than the fixes:

| what | why nothing below this level saw it |
| --- | --- |
| `KompotUpdateBroadcaster` bound by nothing — **the server could not start** | every route test builds its own graph and supplies its own |
| the application's `Json` registered none of petich's payloads — **no purchase could be created** | every saga test assembles that module by hand |
| `LoadHistoryUseCase` and `LoadOrderScreenUseCase` injected and never bound — two screens answered 500 | Koin resolves lazily, so the process starts and the health check passes |
| the container healthcheck ran `/dev/tcp` under `sh`, which is dash — **permanently unhealthy** | nothing waited on the healthcheck until `depends_on: service_healthy` did |

Three of the four are now caught below the stand as well: `RoutesResolveWhatTheyInjectTest` reads what
the routes inject and checks the application binds it, and it was proved to bite by removing one of
the two bindings above. The fourth — a healthcheck that cannot pass — is caught by `--wait` and by
nothing else, which is an argument for the stand rather than against it.

Also found and fixed on the way up: the Postgres volume was mounted at `/var/lib/postgresql/data`,
the pre-18 convention. The 18 image refuses to start on it and says why — `pg_upgrade --link` would
cross a mount-point boundary.

- Anchors: `deploy/compose.yaml`, `e2e/src/test/kotlin/io/konekt/e2e/`.

Background: [research-stack](../research/research-stack.md) D21,
[research-architecture](../research/research-architecture.md) §1.8.

## The combination every other test here cannot produce

Expand-and-contract is a claim about a rolling deploy: during one, the new schema and the previous
version's code are live at the same time, and the migration must leave that version working. Every
test in this repository runs the **new code against the new schema** — precisely the pair that cannot
fail. A migration that dropped a column, renamed one, or added a `NOT NULL` without a default would be
green in all of them and take the running fleet down on deploy.

Proved to bite, both directions, and the container proved to be the old one rather than the new one
wearing a different name: `/api/v1/dev/fail` answers 500 on the current server and **404 on the
previous**, because that route did not exist at that commit.

| | Result |
|---|---|
| the real V10 against the 8c8c958 server | green — sign-in and a full purchase saga on old code |
| a `RENAME COLUMN subscriber.msisdn` added on top | red, both tests |

## The check had the defect it exists to catch

The first mutation run left the renamed column behind, and **deleting the migration file did not
un-rename it**. The next run measured what the last one had left rather than what the tree said, and
stayed red on a tree that was fine. So the script tears the stand down first: a schema is cumulative
and a stand is not torn down between runs, so "start from the tree's schema" has to be done rather
than assumed.

That is the same shape as the stale evidence that made a tracy assertion vacuous earlier the same day,
arriving from the opposite direction — there a leftover made a broken thing look green, here it made a
working thing look broken.

## Why it is not in `check` or in `e2e`

It tears the stand down and rebuilds an old server: minutes rather than seconds. And its subject is a
PAIR of versions rather than this one, so it belongs with a release rather than with every commit — a
suite that fails for reasons unrelated to the change is a suite people mute, and this one would fail
on every branch whose ref has no migrations of its own.
