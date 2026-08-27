---
id: B-62
title: "Three tests accused the services that were working; a local daemon held their ports"
status: done
priority: P1
size: S
stage: stage-m4-proof
epic: feature-observability
---

# B-62 — The failure named katcher, and katcher was fine

`ObservabilityScenarioTest` had three tests red — a crash katcher would not show, an ingest answering
404 where 405 was expected, and tracy storing nothing for `konekt-server`. Every message pointed at
the observability containers.

They were all working. An unrelated local daemon was listening on `127.0.0.1:8091` and
`127.0.0.1:8092` — the host ports the stand publishes tracy and katcher on — while docker held the
wildcard `*:8091` / `*:8092`. A client connecting to `127.0.0.1` gets the loopback binding, so every
request went to the other process, which answered `404` to each GET and `501` to each POST. katcher's
own log showed it symbolicating crashes throughout.

**It was verified rather than assumed.** The same three failed against
`ghcr.io/youndie/konekt-server:v0.1.7` — the released image built before any of the work that was
suspected — which is what separates "my change broke it" from "this was already true". Then `lsof`
named the holder.

- **Two fixes, and only the second is general.** The published ports move out of the `808x`/`809x`
  band, where everything run locally lands, to `819x`. That fixes this machine and relocates the coin
  flip; it does not make the next collision legible.
- **`Stand.standDiagnosis` now names a shadowed port**, so an expired wait says *"port 8192 is held
  by: Python 18663"* beside the container list instead of accusing the service. Ownership rather than
  a probe: no request distinguishes "this is katcher" from "this is something else serving JSON", and
  the fact that decides it is who holds the socket. A machine without `lsof` gets nothing extra
  rather than a wrong answer.
- **Not covered:** the ports are still fixed numbers. Ephemeral publishing with the mapping read back
  from `docker compose port` would remove the class — and it means every URL in the suite becomes a
  lookup, which is a bigger change than the bug currently justifies.
- AC: `make e2e` is green on a machine where something else holds a port in the old band.
- AC: a wait that expires while a stranger holds one of the stand's ports says so in its message.
- Anchors: `deploy/compose.yaml`, `e2e/src/test/kotlin/io/konekt/e2e/Stand.kt`,
  `e2e/build.gradle.kts`.

The shape is one this repository already knows from the other side: a symptom that names a component
is not a mechanism, and the component it names is the one worth clearing first.
