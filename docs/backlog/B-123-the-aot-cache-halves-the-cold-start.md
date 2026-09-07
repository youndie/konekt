---
id: B-123
title: "A Leyden AOT cache trained inside the image halves the cold start; whether to ship it"
status: done
priority: P2
size: M
stage: stage-m7-completeness
---

# B-123 — The AOT cache, measured on this stand

§6 of [research-measurements](../research/research-measurements.md) put the cold start at five to
seven seconds on one core and the first request at up to a second, and said the readiness probe
allows for it but nothing warms the pod. Project Leyden's AOT cache (JDK 25, JEP 514) is the
mechanism aimed at exactly that, and [zavarnik](https://github.com/youndie/zavarnik) is the
toolkit that trains and verifies one through the `application` plugin's start script — the seventh
toolkit of the portfolio, and this is its first use on a service that was not written for it.

**What was done, on a branch and as an experiment.** The plugin applied to `:server` with the
k6 `screens` path as the training workload (sign-in through the dev OTP readback, twenty passes
over three screens). The application does not start without Postgres and the broker, so training
cannot happen in `docker build`: `scripts/measure/aot-coldstart.sh` trains inside a container of
the stand image on the stand's network through the runner the plugin ships in `lib/`, lays the
cache over the image as one more layer (`konekt-server:local-aot`), has the runner verify it there
under `-XX:AOTMode=on`, and runs `coldstart.sh` against both images in alternation. The JVM
compares jar mtimes with the cache, and the runner's own pinning happens in a container the image
never sees; the first run pinned them on the host before `docker build`, and the plugin now does it
in `installDist` itself (zavarnik `B-28`, `0.1.0.11`) — re-run on that version with no host-side
step, the verification in the second image loaded 5 217 of 5 220 application classes from the cache.

**What came out** (2026-09-07, build box, chart limits; the raw record is
[`measurements-2026-09-07/aot/`](../research/measurements-2026-09-07/aot/README.md)):

| | without the cache, median of 10 | with the cache, median of 10 |
|---|---|---|
| `docker start` → `/health` | 4 380 ms (4 186–5 877) | **2 042 ms** (1 915–2 227, one restart at 4 128) |
| first home screen | 510 ms (327–827) | **240 ms** (195–396) |
| p50 of the next hundred | 9.3 ms | 8.8 ms |
| p95 of the next hundred | 89 ms | 97 ms |

The cache is 65 MiB over 128 jars and adds 83 MB to the image (602 → 685 MB). Under
`-XX:AOTMode=on` the verification loaded 5 329 of 5 332 application classes from it.

**What it says.** Readiness halves and so does the first request; the hundred after it do not
move, because the cache holds classes, heap objects and method profiles, not compiled code — the
JIT still compiles the request path, only from a warm profile. This clears the threshold the
experiment was given (20 % of readiness) by a wide margin.

**The same server as a Jib image** (the same day, `scripts/measure/aot-coldstart-jib.sh`): `:server`
gains `jib { }` — `eclipse-temurin:25-jre`, `packaged`, user 10001 — and zavarnik's Jib mode trains
inside a container of the Jib image on the stand's network (`zavarnik { jib { dockerRunArgs } }`
fed from `-Pkonekt.aotDocker`), the next Jib build carries the cache as a layer with
`-XX:AOTCache` in the entrypoint, `jibAotVerify` checks it there: 4 727 of 4 730 application
classes from the cache. The record is
[`measurements-2026-09-07/aot-jib/`](../research/measurements-2026-09-07/aot-jib/README.md):

| | Jib image without the cache | Jib image with the cache |
|---|---|---|
| `docker start` → `/health`, median of 10 | 6 244 ms (round 2 alone: 4 253 ms) | **2 242 ms** |
| the same, range | 3 396–21 472 ms; round 1 had four restarts over 8 s | 1 476–2 729 ms |
| first home screen, median | 309 ms | **168 ms** |
| p50 / p95 of the next hundred | 10 / 74 ms | 8 / 90 ms |
| cache / image | — | 61 MB / 614 MB against 537 |

Round 1 of the image without the cache is the noisy one — four restarts between 8.5 and 21.5 s
right after the Jib builds and the training on the same box — and round 2 (3.4–8.2 s, median
4.3 s) is the Dockerfile baseline again. Both paths land in the same place: about two seconds to
`/health` on one core against four and a half. Two things the Jib path taught: Jib 3.5.4 does not
support the configuration cache this build has on (`--no-configuration-cache` on its tasks), and
a Jib image declares no `HEALTHCHECK`, so `compose up --wait` returns on "running" and the
measurement has to wait for `/health` itself.

**What shipping it would take, and this item decides whether to:**

- training becomes a step of the release: `publish-image.yaml` has no stand today (`B-119` is
  about the same gap), and an image with a cache trained elsewhere is an image that starts
  without it — the JVM refuses a cache from another JDK build silently;
- the readiness probe in the chart waits `initialDelaySeconds: 5`, so a pod ready in two seconds
  is still marked ready at five — the probe has to be retuned for the number to reach a rollout;
- the verification runs on the stand, not on `check` (`verify { onCheck = false }`), which is a
  second thing the release must run;
- 83 MB more image per release, pulled by every node.

- AC (done): the experiment script, the plugin applied, and the numbers above with their record.
- AC (done): the decision — **ship**, taken by the owner on 2026-09-07 — and the three items:
  `scripts/aot-image.sh` trains and verifies the cache inside the release image, run by
  `publish-image.yaml` before the push and by `make release-image`; the `verify` job verifies the
  cache in the pulled image; the chart (`0.2.4`) gained a startup probe every second and lost the
  readiness probe's initial delay. What is deliberately not shipped: the Jib path — the Dockerfile
  stays the release image, Jib is the second image the measurement used.
- Not covered: the 83 MB the cache adds to every release is accepted, not reduced; a warm-up
  request in the probe (the first subscriber's 240 ms) is still an open idea, not a task.
- Anchors: `server/build.gradle.kts` (the `zavarnik { }` block), `scripts/aot-image.sh`,
  `.github/workflows/publish-image.yaml`, `scripts/measure/aot-coldstart.sh`,
  `docs/research/measurements-2026-09-07/aot/`, `charts/konekt/templates/server.yaml` (the probes).

**Rolled out 2026-09-08.** `v0.1.41` reached the cluster and the JVM refused its cache — the
start script's `lib/*` expands in the filesystem's order, and the k0s node's containerd ordered
it differently from the CI runner's overlay2, so the cache recorded one classpath string and the
node presented another. Rolled back; the script now lists the jars (`5c6e89e`), zavarnik refuses
a wildcard (its `B-31`), and `v0.1.42` (revision 48, chart `0.2.4`) runs with the cache: the pod
was Ready 3 s after its container started against 11 s for `v0.1.40` under the old probes, the
log shows no `[aot]` line, `deploy-check` agrees on 34 values. The same deploy hit the broker's
segment size (`B-124`), which the release now overrides.
