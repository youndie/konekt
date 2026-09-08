---
id: B-124
title: "The chart's broker segment size cannot be deployed over the broker's existing log"
status: done
priority: P2
size: S
stage: stage-m7-completeness
---

# B-124 — 32 MiB segments in `values.yaml`, 512 MiB segments on the volume

Found by the first deploy of chart `0.2.3`+ (`B-123`, 2026-09-08). `B-100` set
`broker.segmentBytes: "33554432"` and `B-106` made the value reach the container; until then the
cluster's broker had run with the env **empty** and booblik's own default of 512 MiB, and the log
on the volume has segments of that size. booblik 0.3.1 refuses to open a log under a smaller
capacity — "Lowering the capacity under an existing log would discard …" — and the broker
crash-looped; without the broker the server never bound its port, the startup probe restarted
it, and `helm --wait` timed out. Rolled back to revision 45.

The deploy that went through (`v0.1.42`, revision 48) carries
`--set-string broker.segmentBytes=536870912` — the size the log already has — so the release's
values now say 512 MiB while `values.yaml` says 32 MiB. `deploy-check` renders with the release's
values and is green, which is exactly the case it was written for: it compares the cluster with
the chart *as deployed*, not with the chart's defaults.

- Open: decide which number is right for this deployment. Either `values.yaml` moves to 512 MiB
  (then the override goes away and the two agree), or the broker's volume is reset so the 32 MiB
  default can apply — a reset loses the log, which on this stand is fictional traffic, but that is
  the owner's call, not a deploy step's.
- Anchors: `charts/konekt/values.yaml` (`broker.segmentBytes`), `docs/backlog/B-100-*.md`,
  `docs/backlog/B-106-*.md`, `docs/backlog/B-123-the-aot-cache-halves-the-cold-start.md`.

**Decided 2026-09-08, the owner: the log stays, the number moves.** `values.yaml` says 512 MiB
(chart `0.2.5`), and so does `deploy/compose.yaml`, which `ComposeStandTest` pairs with it.
Retention moved with it to 1 GiB per partition — a bound below one segment deletes nothing, as the
compose file already said — and the volume's 2 GiB claim stays where it was: `local-path` neither
enforces nor expands it, and a changed `volumeClaimTemplates` size would be refused by the
StatefulSet. The release's override is gone: revision 49 was upgraded with `--reset-values` and
the release's own user values minus that key, and `helm get values` no longer names it.
