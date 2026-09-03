---
id: konekt-broker
title: booblik broker (the stand's message bus)
type: service
status: active
repo_url: https://github.com/youndie/konekt
module: deploy/compose.yaml
tech_stack: [booblik 0.3.1, container image ghcr.io/youndie/booblik, plaintext TCP]
owner: unassigned
depends_on:
  - konekt-server (the only producer and the only consumer)
publishes:
  - topic orders
  - topic usage
  - topic notifications
---

# booblik broker

> A service konekt runs but does not write: the image is `ghcr.io/youndie/booblik:0.3.1`. What is
> documented here is **konekt's side of it** — how it is configured, what talks to it, and the three
> properties that catch people out. Everything below was read out of `deploy/compose.yaml` and the
> files in §2a on 2026-08-25; nothing was read out of booblik's own sources, and where a claim would
> need them it says *not covered*.

## 1. Responsibility

It carries events between parts of konekt that are deliberately not calling each other: the outbox
relay publishes `purchase.completed` and `purchase.reversed` to `orders`, and the traffic simulator
publishes to `usage` for its own consumer to read back. Nothing else produces and nothing else
consumes.

What it does not do: **it does not create topics**, and it does not store consumer offsets.

## 2. API contracts

There is no HTTP surface. The wire is booblik's own plaintext protocol, spoken through
`ru.workinprogress.booblik.net.client` — see the anchors below for the only two call sites.

## 2a. Code anchors

| File | What is there |
|---|---|
| `deploy/compose.yaml` | the `broker` service: image, `BOOBLIK_TOPICS`, the deliberate absence of `ports` |
| `server/src/main/kotlin/io/konekt/events/EventTopics.kt` | which event type routes to which topic |
| `server/src/main/kotlin/io/konekt/events/BrokerConnection.kt` | the one connection and the one producer for the process |
| `server/src/main/kotlin/io/konekt/events/BooblikOutboxPublisher.kt` | petich's outbox transport |
| `server/src/main/kotlin/io/konekt/mocks/traffic/UsageConsumer.kt` | the only consumer |
| `server/src/main/kotlin/io/konekt/mocks/traffic/UsageChain.kt` | what starts it, always |
| `server/src/main/kotlin/io/konekt/mocks/traffic/TrafficChain.kt` | what starts the simulator, behind `SIMULATE_TRAFFIC` |

## 3. How it is built

**The topic set is fixed at startup, in two files that must agree.** `BOOBLIK_TOPICS` in
`deploy/compose.yaml` reads `orders:1,usage:1,notifications:1`, and `EventTopics` decides which type
goes where by prefix (`purchase.` → `orders`, `usage.` → `usage`, `notification.` →
`notifications`, anything else → an error rather than a default). An event routed to a topic that
does not exist is a publish that fails forever and a stuck outbox, so both halves are paired by
tests: `BrokerTopicsTest` and `ComposeStandTest`.

**One partition per topic.** Ordering inside a topic is worth more here than parallelism nobody has
measured a need for: every event about one order must arrive in the order it happened, and an order
exists only inside a partition. The publisher therefore keys by `orderId` read out of the payload —
keying by the event id would spread one order's story across partitions and lose exactly that.

**Delivery is at-least-once and the consumer is expected to cope.** The event id is
`<orderId>:<type>`, which is stable across redeliveries, so a consumer keyed on it can tell a
redelivery from a second purchase.

## 4. Dependencies

| Kind | Name | What for |
|---|---|---|
| Service | [konekt-server](konekt-server.md) | the only process that connects to it |
| Storage | volume `broker-data` at `/var/lib/booblik` | its own log |

## 5. Infrastructure and deploy

- **Image:** `ghcr.io/youndie/booblik:0.3.1`, pinned to `linux/amd64` by
  `platform: ${BROKER_PLATFORM:-linux/amd64}` so an arm64 host emulates it with a warning rather than
  failing with "no matching manifest", which reads like a broken tag.
- **Ports:** none, deliberately. See §8.
- **Health:** the compose file waits on `condition: service_started` for the broker, not on a
  healthcheck — *not covered:* whether the image ships one.

## 6. Local setup

It comes up with the rest of the stand:

```bash
make stand-up
```

## 7. Configuration

| Key | Description | Required |
|---|---|---|
| `BOOBLIK_TOPICS` | the whole topic set, `name:partitions` comma-separated, fixed at startup | yes |
| `BOOBLIK_SEGMENT_CAPACITY_BYTES` | 32 MiB — the unit retention deletes in, see below | set here |
| `BOOBLIK_RETENTION_BYTES` | 128 MiB **per partition** of closed segments | set here |
| `BOOBLIK_RETENTION_MILLIS` | six hours | set here |
| `BROKER_PLATFORM` | overrides the pinned image platform | no |

**What is kept, and why the segment size is in that table.** booblik deletes nothing unless a
retention bound is set — the worker returns on its first line when both are absent — so this
deployment kept every event it had ever published until `B-100`. Retention drops **whole segments and
never the active one**, which makes the segment size the load-bearing number rather than the bound:
against booblik's 512 MiB default, this product's traffic closes a segment about every thirty hours,
and a bound of any size deletes nothing until one closes. At 32 MiB the busy partition rolls about
every two hours. The two numbers must be set in both `charts/konekt/values.yaml` and
`deploy/compose.yaml`, and `ComposeStandTest` refuses files that disagree — or a bound that is
smaller than a segment, which is a pair of plausible numbers that does nothing.

**AND `0.3.1` REFUSES A CAPACITY SMALLER THAN THE LOG ALREADY ON DISK**, which is worth knowing
before an upgrade rather than during one. `B-107` found the earlier behaviour: opening an existing
MAPPED log under a capacity smaller than it was written with silently made everything past the new
limit unreachable — nothing freed, nothing readable — and `truncateTo` then made it permanent.
[booblik#25](https://github.com/youndie/booblik/issues/25) closed it by refusing, released in
`0.3.1`, which this deployment pins.

So the upgrade turns a silent loss into a **refused start** on exactly one shape of volume: one whose
segments were written under a larger capacity than the deployment now sets. This product went the
other way — `B-100` cut the capacity from booblik's 512 MiB default to 32 MiB — so a broker volume
predating that change is precisely that shape, and the honest fix is an empty volume rather than a
larger number, since what the old segments hold is usage nobody replays.

**Retention costs this product nothing**, and that is what makes six hours safe: the usage consumer
starts from the END of the log, so no record here is ever read a second time. See §8.

That sentence was **false when it was written**, and it was the reason six hours looked safe. Until
[B-108](../backlog/B-108-the-usage-consumer-starts-a-megabyte-from-the-beginning.md) the consumer
started one megabyte in from the START of the log — measured on the stage deployment, offset 11915
against a log ending at 374473 — so every restart replayed what retention was about to delete, and a
retention bound was not a bound on what gets applied but a bound on how much gets replayed. It reads
the high watermark from METADATA now, and `TrafficChainTest` publishes *before* the chain starts to
hold it to that.

The server's half is `BROKER_HOST` (default `broker`) and `BROKER_PORT` (default `9092`) in
`server/src/main/kotlin/io/konekt/KonektConfig.kt`.

## 8. Quirks

- **No published host port, and a test enforces it.** The protocol has neither TLS nor
  authentication — both deliberately absent, being incompatible with the zero-copy read path — so
  network reachability is the whole of its security model, and it is one line of YAML away from not
  being true. `ComposeStandTest` reads the compose file and fails if the broker's block publishes a mapping to
  `:9092`. Precisely that — a port published to some other container port would pass, which is worth
  knowing before trusting the guard with a change to the broker's own port.
- **Adding a fourth topic is a broker restart**, which makes topic naming an architectural decision
  rather than a runtime one.
- **There are no consumer offsets.** That absence is what removes the group coordinator and the
  cluster consensus behind it, and it moves the problem into konekt: `UsageChain` resumes from
  wherever the broker is **now** rather than from zero, because replaying a day of simulated usage on
  every restart would empty every counter in the product.

  **"Now" is asked of METADATA, and asking it any other way is how `B-108` happened.** A fetch cannot
  answer it: one poll from offset zero lands one `maxBytes` in from the start, which equals the end
  only while the log is shorter than a single poll — true of every test in this build and of no
  deployment that has been running a day. It also stops working outright once retention moves the
  log's start above zero, because a fetch below the start is `OFFSET_OUT_OF_RANGE`.

  Which means **usage published while the server is down is not applied when it comes back.** For a
  simulated feed that is the right answer; for a real one it is the first thing that would have to
  change, and what it would take is a position this application stores itself — a table, updated per
  batch, with the redelivery questions that opens. Stated so the next consumer does not inherit the
  choice by copying it.
- **The consumer runs on every replica, and that is not the same problem the simulator has.** Each pod
  polls the same partition from wherever the broker is when it starts, so N pods apply each event N
  times: a 25 MB decrement becomes 50 MB with two of them. There is no guard in the chart for it, and
  the honest statement is that **this build is a single-instance deployment** — `konekt-server.md`
  carries what is per-replica, `reference-scope.md` carries horizontal scale as a non-goal, and the
  chart refuses only the simulator above one replica because that one drains allowances on a timer
  rather than on traffic.
- **`UsageChain` and `TrafficChain` are separate starters, and the split is load-bearing.** The
  consumer is the product's own worker and starts whenever the application does; the simulator is a
  mock and starts behind `SIMULATE_TRAFFIC`. They were one starter until `B-89`, which meant that with
  the flag off — the default, and what the chart requires above one replica — **no process in this
  build read the `usage` topic at all**: the broker accepted events and nobody applied them.
- **A broker restart used to be permanent.** `BooblikConnection` opens one socket in its constructor
  and never dials again — the position and the socket both live on the client, which is the same
  decision that removes the coordinator — so a replaced pod left the consumer, the outbox relay and
  the simulator all holding a dead socket until somebody restarted the server.
  [B-107](../backlog/B-107-a-smaller-segment-truncates-the-log-and-wedges-the-consumer.md) gave
  `BrokerConnection` a generation and a `reconnect`, and every holder of a `Producer` or a
  `TopicHandle` re-resolves it when that number moves. The part worth remembering is what the first
  fix got wrong: reconnecting from inside the `catch` block threw when the replacement pod was still
  starting, which left the `while` loop and killed the consumer — and in a log that reads like a
  success, because the noise stops either way.
- **The producer is an accumulator with a coroutine of its own**, held per process rather than per
  call: it batches for a few milliseconds before sending. Creating one per event would give up the
  largest factor in the broker and leave a coroutine behind on every publish. The figure booblik
  quotes for that batching is 54×; that number was **not** measured here.
