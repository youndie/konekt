---
id: B-13
title: "booblik in the compose file, with its three topics declared at startup"
status: done
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

- AC OK: `BrokerTopicsTest` starts the **published image** in a container with the compose file's own
  `BOOBLIK_TOPICS`, then writes to each topic and reads back what it wrote. Asking the broker for
  metadata would have been a weaker claim — a topic can be listed and unusable — and the round trip
  is also the only way to notice a topic declared with zero partitions, which metadata reports
  happily and a producer cannot write to.
- AC OK: `ComposeStandTest` reads the compose file and fails if the broker publishes a port. One line
  of YAML is the whole of the broker's security model — it has neither TLS nor authentication, both
  deliberately absent as incompatible with its zero-copy read path — and "not published" is a thing a
  reviewer glances past and a test does not.
- Also: the topics are declared in two files, so a third test pairs them. Routing an event to a topic
  the broker does not have is a publish that fails **forever**, because booblik creates nothing on
  demand, and the symptom is a stuck outbox rather than a missing topic.
- No `booblik.conf`: the broker takes `BOOBLIK_TOPICS` from the environment and needs no file, so the
  anchor this item was written with does not exist.
- Anchors: `deploy/compose.yaml`, `deploy/Dockerfile`,
  `server/src/main/kotlin/io/konekt/events/EventTopics.kt`,
  `server/src/test/kotlin/io/konekt/events/BrokerTopicsTest.kt`,
  `server/src/test/kotlin/io/konekt/events/ComposeStandTest.kt`.

Background: [research-architecture](../research/research-architecture.md) §1.8, Risk 6.
