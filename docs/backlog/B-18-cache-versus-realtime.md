---
id: B-18
title: "Answer in writing how the screen cache and a live update interact"
status: question
priority: P2
size: S
stage: stage-m2-live
blocked_by: [B-15]
---

# B-18 — Answer in writing how the screen cache and a live update interact

`kompot-client-cache` is a cache-first provider with ETag revalidation; realtime replaces a component
by id in an already-rendered tree. Nothing read so far says what a cold start does with a screen whose
cached copy predates an update that has already been applied and discarded.

- **The decision and its reason.** This is open question 1 in the research, and the resolution is a
  written answer with the counter screen as the subject — not a code change decided in advance. The
  hypothesis on record is that the cache stores the screen as fetched and updates apply on top in
  memory, so a cold start shows the stale value for exactly one request.
- The rejected alternative is disabling the cache for screens that have an update channel. It removes
  the question rather than answering it, and offline-first is one of the reasons the cache is here.
- Not covered: what to do if the hypothesis is wrong. That becomes its own item, with the refutation
  written into research §1 at the point of divergence.

- AC: research §1 carries either the confirmation or the refutation, with what was observed.
- AC: if the stale-for-one-request behaviour is confirmed, the counter screen states its own freshness.
- Anchors: `client/src/commonMain/kotlin/io/konekt/cache/`.

Background: [research-architecture](../research/research-architecture.md) open question 1.
