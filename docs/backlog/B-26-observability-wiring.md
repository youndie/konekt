---
id: B-26
title: "metrik, tracy and katcher wired, and a compose file that runs all three"
status: wip
priority: P1
size: M
stage: stage-m4-proof
blocked_by: [B-08]
---

# B-26 — metrik, tracy and katcher wired, and a compose file that runs all three

Three self-hosted binaries beside the server: metrik ingesting over UDP `:9999`, tracy over HTTP with
an ingest key, katcher taking server errors. The value being demonstrated is one purchase visible in
all three at once — in tracy by `orderId`, in metrik as latency on the route, in katcher if it fell
over.

- **The decision and its reason.** tracy's entity fields are indexed on `msisdn`, `iccid` and
  `orderId`, because "show me everything that happened to this order" is the question a demonstration
  of an incident is built around, and it is only answerable if those were indexed when written. tracy
  logging is `suspend` by design — it runs inside the request and the trace context lives in the
  coroutine — so it cannot be called from a non-suspending helper, which shapes where logging sits.
- The rejected alternative is one of the three plus stdout for the rest. It halves the setup and
  removes the only reason to have three.
- Not covered: alerting thresholds. metrik's alerts exist; tuning them needs traffic this build does
  not have.

- AC: one purchase produces a tracy trace reachable by `orderId`, a metrik data point on the route,
  and a katcher report if the route throws.
- AC: an agent switched off is visible as absent data, not as healthy silence — asserted by a check
  that fails when a service reports nothing after a run.
- AC, **carried from `B-05`**: konekt's `KompotDegradationSink` reaches tracy with `originalType` as
  an indexed field, and leaves a katcher breadcrumb. The mechanism and the reported-once property are
  done and tested; what is missing is where the record goes, which is this item's subject. Until it
  lands, an unknown component is drawn correctly and counted nowhere — which is the exact blindness
  kompot#81 was filed about.

- Anchors: `server/src/main/kotlin/io/konekt/observability/`, `deploy/compose.yaml`.

Background: [research-architecture](../research/research-architecture.md) §1.9.

## What landed

Three services in the stand and three agents in the server, with the trio deliberately having no
`depends_on` relationship with it in either direction: an agent that cannot reach its collector must
not stop the product from serving, and wiring the dependency would hide exactly that — the stand would
refuse to start instead of showing what a running system does when its observability is down.

| | Wired as | Measured on the stand |
|---|---|---|
| metrik | Ktor plugin, UDP to `metrik:9999` | `konekt-server` at 0.27 rps, p95 27.95 ms |
| tracy | agent + delivery + `install(Tracy)` | 4 stored records, `entityRefs: {orderId: 4, subscriberId: 4}` |
| katcher | `Katcher.start` — the same object the iOS client uses | ingest answers; nothing has thrown yet |

**Each agent is all-or-nothing, and a half-configured one refuses at startup.** All three answer a
missing endpoint or key by doing nothing — metrik has an `enabled` flag, tracy's delivery never
connects, katcher's `start` prints a line and returns. That is three ways to arrive at one failure: a
deployment that meant to be observed and is silent. `<NAME>_ENDPOINT` without `<NAME>_KEY` is an error
where it is configured; both absent is a decision.

**The order id is indexed at the point it is written**, which is the only place it can be. tracy turns
an indexed field into an entity key, so "show me everything that happened to this order" is answerable
— and the same log line without the flag produces a record tracy stores and nobody can find.

## AC 2, and it is the one that made the rest non-vacuous

`ObservabilityScenarioTest` drives a real purchase and then asks each collector whether anything
arrived, failing on zero. Not on presence: a service row can exist from a handshake, so the assertion
is on a non-zero request rate and on a non-zero count of `orderId` entity refs.

**Proved to bite, and the first two attempts did not.** Restarting the collectors was not enough — the
test creates its own traffic, so fresh data arrives. Emptying them was not enough either, because
`docker compose rm -v` removes anonymous volumes and these are named. With the agents switched off AND
the named volumes gone, it fails by name:

```
java.lang.AssertionError: waited 20s for: metrik to have seen konekt-server
```

That is why the compose file lets both variables of a pair be overridden to empty: running the stand
with an agent off is the only way to check that this assertion can fail at all.

## The check was measured where it could not fail, and CI said so

It passed locally and failed in CI on the next push, and the timeout was not the reason.

**The metrik agent's aggregation window defaults to sixty seconds** — read in
`metrik/shared/.../Protocol.kt` rather than recalled. The agent buffers a window and sends it when the
window closes, so a process that has just started reports nothing at all for a minute. Every local run
was against a stand that had been up for a while, where a window had long since closed; CI starts a
fresh one and the whole e2e run is shorter than a single window.

The fix is the window rather than the timeout: `METRIK_WINDOW_MS` is five seconds on the stand, which
is a stand-specific setting with the same justification as tracy's `sampleRate = 1.0` beside it. Then
verified the way it should have been the first time — `docker compose down -v`, a cold stand, the
whole suite green in fifteen seconds, which a sixty-second window could not have produced.

That the local port had to move to do it is the compose file's own design paying off: another
container held 55432, and every port in the stand is overridable precisely so the stand is not the
thing that refuses to start.

## The degradation sink is bound, and where it can land is measured rather than assumed

The renderer has reported an unknown component through kompot's sink since `B-05`, and konekt bound no
sink — so the toolkit's default took it, an unknown component was drawn correctly and **counted by
nothing**, and the blindness [kompot#81](https://github.com/youndie/kompot/issues/81) was filed about
survived being fixed upstream. A placeholder nobody counts is indistinguishable from a screen that
never degraded.

`KonektApp` now provides a `KonektDegradationSink` around the whole tree, and `:client:standTest`
asserts by DATA: rendering `B-25`'s development screen against the running stand produces two records,
both naming `esim_transfer_widget`, neither marked as a fallback — a hole and a substitution are
different facts about a screen. Proved by unbinding the sink.

**The output is a parameter and not a dependency, and the reason is a measurement.** What a client can
report to differs by platform: katcher publishes every Apple target since `client:0.6.2`, while
tracy's agent publishes `jvm`, `linux_arm64`, `linux_x64` and `macos_arm64` and **no iOS target at
all** — the published listing has `agent-jvm`, `agent-linuxarm64`, `agent-linuxx64`, `agent-macosarm64`
and nothing else, with no separate coordinate carrying the rest.

So the third AC's "reaches tracy" is not merely unfinished, it is **unavailable on the platform where
it matters most**: a phone updates on the subscriber's schedule and a desktop build updates on ours, so
an out-of-date client is likeliest exactly where the record cannot be sent. Filed as
[youndie/tracy#16](https://github.com/youndie/tracy/issues/16) — the same shape as katcher#25, which
closed and gave konekt's iOS build crash reporting.

The default output is named `KonektApp.RECORDS_NOTHING` rather than written as `{ }` at the call site:
an empty lambda reads as "nothing to do here", and a deployment reporting nothing should be one that
chose to rather than one that forgot.

## The record fired on every recomposition, and the test that said otherwise passed for another reason

This item's third AC said "the mechanism and the reported-once property are done and tested". The
mechanism was; the property was not. `UnknownBlockRenderer` called the sink **in the composable body**,
so it reported on every recomposition — the count an operator reads was a function of how often
Compose redrew rather than of how many components failed to render.

`UnknownBlockRendererTest` asserted `1` and passed, because its fixture composes once and never again.
It was right about the number and wrong about the reason, which is the shape worth catching: a test
that cannot fail proves nothing about the property it names.

It surfaced in CI rather than locally, and the difference is the whole story: a theme arriving
mid-composition — `B-22`, landed the same day — caused one extra pass, and the stand assertion of
exactly two records saw three. A looser assertion would have hidden it.

The report is inside a `LaunchedEffect` keyed by the component now, so a redraw of the same one is not
a second degradation while a node replaced by a live update is. `a redraw of the same component is not
a second degradation` nudges a real state three times and asserts the count did not move; proved by
putting the call back in the body.

## AC 3 is met: the degradation reaches tracy, findable by wire type

tracy `0.1.13` publishes `ios_arm64`, `ios_simulator_arm64` and `ios_x64` — U11 / youndie/tracy#16,
fixed the same day this item's blocker was written down. Verified in the module metadata rather than
read off a commit message, and then by compiling `:client` for both Apple targets with the agent in
`commonMain`.

So `KonektClientObservability` is `commonMain` and the record goes to the same two places from a
desktop window and from a phone: a tracy line whose `originalType` is **indexed**, and a katcher
breadcrumb attached to whatever crashes next. Both agents moved out of `iosMain` — katcher because a
breadcrumb is not a crash and has to be left wherever a screen degrades.

**Measured at the far end.** `DegradationReachesTracyTest` renders B-25's forward-compat screen with a
real agent pointed at the stand's tracy, and tracy then holds:

```
{"name":"konekt-client","storedRecords":2,"entityRefs":{"originalType":2}}
```

**And the first version of that test was vacuous, which a mutation proved rather than a suspicion.**
Removing `indexed = true` left it green: tracy's service row is CUMULATIVE, so "has it any refs" was
answered by the previous run's data. The assertion is a DELTA now — two more than the baseline read
before anything is rendered — and the same mutation kills it. A collector that accumulates cannot be
asked "did it happen", only "did it happen again".

## AC 1's katcher half: the wiring was missing, and it was invisible from every angle

The route that throws is `/api/v1/dev/fail`, behind `DEV_SCREENS` like every other demonstration
control and for a sharper reason: a route that reliably answers 500 is a denial-of-service primitive
if it ships.

Adding it found something the address check could never have shown. **`Katcher.start` installs an
uncaught-exception handler, and a route's exception never reaches one** — `StatusPages` catches it and
answers 500, which is that plugin's entire purpose. So the server's katcher was correctly configured,
correctly started, answered on its ingest address, and was structurally unable to receive anything a
route did. The handler that swallows the exception is the only place that can report it, and it does
now: `Katcher.catch(cause, context = route + method)`, guarded by
`StatusPagesReportsToKatcherTest` — which also refuses the reverse mistake, a domain refusal reported
as a crash. A 404 for somebody else's order is an ANSWER, and crash groups full of the product working
correctly are crash groups an operator learns to ignore.

## What delivery to katcher still needs, measured rather than guessed

The report is produced and cannot yet arrive, and the two reasons are specific:

- **The stand's katcher has no application and no user.** Read out of its own database:
  `apps: 0`, `app_keys: 0`, `users: 0`, `reports: 0`. An ingest carrying `konekt-server` names an app
  key that katcher does not know, so it is refused. Provisioning one at stand startup is the missing
  piece, and it is a fixture rather than a line of product code.
- **Its data volume is mounted where it does not write.** `compose.yaml` mounts `katcher-data:/data`;
  `docker diff` shows the database at `/app/data/local.db`. The volume is empty and the stand's
  katcher therefore loses everything on recreate. Harmless for a demonstration and wrong, and it was
  found by measuring the wrong directory first — the volume showed zero files both before and after a
  deliberate crash, which is what a working pipeline would also have shown there.

## `wip`, and what is left

- **AC 1's katcher half is produced but not delivered**, for the two reasons above. What changed is
  that "unproved" now names a fixture gap rather than "nothing throws on purpose".
- **The read path trusts `X-Auth-Request-User`.** metrik and tracy sit behind a reverse proxy in a
  real deployment; the stand has none, so the header is simply believed. Fine for reading a stand and
  not fine for anything else — and worth stating because the same header on an INGEST route would be
  a gift to anyone who can reach it.
