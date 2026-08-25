---
id: B-29
title: "File U1–U5 upstream and record what came back"
status: done
priority: P1
size: S
stage: stage-m5-upstream
---

# B-29 — File U1–U5 upstream and record what came back

All five were filed and **all five closed as completed the same day, 2026-08-25, each with code**:
kompot [#80](https://github.com/youndie/kompot/issues/80) (`resolveSurface` now delegates),
[#81](https://github.com/youndie/kompot/issues/81) (`KompotDegradationSink`, three kinds instead of
the one that was asked for), [#82](https://github.com/youndie/kompot/issues/82)
(`CheckboxInputComponent.variant`), petich [#3](https://github.com/youndie/petich/issues/3)
(`onDroppedEvents` and `requireOutbox`), katcher
[#25](https://github.com/youndie/katcher/issues/25) (all three iOS targets, and the host-picked
native target dropped).

Each was verified in the source and in the published artefact rather than in the issue's state —
"closed" and "fixed" are different claims and only one of them is checkable. Four changed konekt's
own plan, and the amendments are written at the point of divergence in the research rather than by
deleting what was there: `B-04` loses its workaround and keeps its guard, `B-05` loses its reporting
half to the toolkit, `B-09` replaces a hand-written assertion with `requireOutbox = true`, and `B-27`
turns from documentation into wiring.

- **The decision and its reason.** Issues, never forks or pull requests into the toolkits, and each
  workaround in konekt carries a comment naming its issue. A workaround copied into the next project
  outlives the illness it was written for; the comment is what lets someone delete it instead of
  inheriting it.
- The rejected alternative is opening pull requests. It reads as more helpful and it moves the
  decision about a toolkit's shape to the person who happened to hit the problem.
- Not covered: acting on the answers. Each accepted proposal becomes its own item, including removing
  the local workaround.

- AC: five issues exist with the numbers written back into the proposals table, and the reply column
  records what landed, read in the artefact.
- AC: no workaround remains that its issue has made unnecessary — the count is zero, because all five
  closed before any of them was built.
- Anchors: `docs/research/research-upstream-proposals.md`.

Background: [research-architecture](../research/research-architecture.md) D9.
