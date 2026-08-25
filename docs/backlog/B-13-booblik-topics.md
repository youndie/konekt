---
id: B-13
title: "booblik in the compose file, with its three topics declared at startup"
status: open
priority: P1
size: S
stage: stage-m2-live
---

# B-13 — booblik in the compose file, with its three topics declared at startup

booblik does not create topics: *"the set of partitions is fixed at startup on purpose"* (research
§1.8). So `orders`, `usage` and `notifications` are a configuration decision made once, and adding a
fourth later is a broker restart. It also carries no TLS and no compression — both incompatible with
its zero-copy path — so it never leaves the compose network.

- **The decision and its reason.** Three topics, named for the event kind rather than the producing
  feature, because a topic is restarted to add and a feature is not. Partition counts stay at one:
  ordering per topic is worth more here than parallelism nobody will measure.
- The rejected alternative is one topic with a type discriminator. It makes the traffic simulator a
  consumer of order events it must skip, and consumer offsets stop meaning anything per kind.
- Not covered: replication. booblik is one process with none, which is honest for a reference build
  and belongs in the operator material rather than a footnote.

- AC: the broker starts from the compose file with the three topics present, verified by a client
  subscribing to each.
- AC: the broker is not published on any host-reachable port.
- Anchors: `deploy/compose.yaml`, `deploy/booblik/booblik.conf`.

Background: [research-architecture](../research/research-architecture.md) §1.8, Risk 6.
