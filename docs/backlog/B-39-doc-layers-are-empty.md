---
id: B-39
title: "The feature, screen and API layers are empty, and the reason they were empty has expired"
status: open
priority: P2
size: M
stage: stage-m4-proof
---

# B-39 — The feature, screen and API layers are empty, and the reason they were empty has expired

`docs/features/`, `docs/screens/`, `docs/api/` and `docs/services/` hold nothing. CLAUDE.md says why —
"there is no code, and a document written ahead of code documents intent as fact" — and that sentence
was true when it was written and is now false. Four feature verticals exist, with routes, a saga, a
wizard and a live channel behind them, and the layer a task belongs to is the one place a reader is
sent that has nothing in it.

- **The decision and its reason.** Write them from the code that exists, not from the backlog. The
  `main` invariant is the whole method: a document describes what is there, and an item describes what
  will be. Anything not yet built stays in its backlog item.
- The rejected alternative is writing them per feature as each is built. It was the plan and it did
  not happen across nine items in a row — which is evidence about the plan rather than about the
  items, and a rule nobody follows is worth replacing rather than restating.
- Not covered: the OpenAPI artefact. That is `B-23`, and it is a build output rather than prose; this
  item is the layer a person reads, including the auth tier per route, which no generator knows.

- AC: every route the server installs appears in a `docs/api/endpoint-*.md` with its auth tier, and
  the tier matches what `Application.kt` actually wraps in `authenticate`.
- AC: `make report` names no feature document without a code anchors table.
- AC: CLAUDE.md's "these four are empty today" paragraph is replaced by a pointer to them.
- Anchors: `docs/features/`, `docs/screens/`, `docs/api/`, `docs/services/`, `CLAUDE.md`.

Background: the layering and the templates are [docs-bootstrap](https://github.com/youndie/docs-bootstrap);
the templates are copied into [`docs/templates/`](../templates/).
