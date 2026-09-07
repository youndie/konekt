---
id: B-123
title: "A Leyden AOT cache trained inside the image halves the cold start; whether to ship it"
status: open
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
under `-XX:AOTMode=on`, and runs `coldstart.sh` against both images in alternation. Jar mtimes
are pinned on the host before the image is built, to the constant the runner pins them to, because
the JVM compares them with the cache and the runner's own pinning happens in a container the image
never sees.

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
- AC (open): a decision — ship (then the three items above become tasks) or record and stop.
- Anchors: `server/build.gradle.kts` (the `zavarnik { }` block), `scripts/measure/aot-coldstart.sh`,
  `docs/research/measurements-2026-09-07/aot/`, `charts/konekt/templates/server.yaml` (the probes).
