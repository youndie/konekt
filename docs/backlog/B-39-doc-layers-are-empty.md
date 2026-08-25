---
id: B-39
title: "The feature, screen and API layers are empty, and the reason they were empty has expired"
status: done
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

**Closed 2026-08-25.** Written from the code: three services, four features with 52 BDD scenarios (47
of them naming a test that exists), four screens and five endpoint documents — the last of which
covers `GET /health`, because "every route the server installs" includes the one route nobody thinks
of as an API. The map is [docs/README.md](../README.md).

Three things were found in the writing and are recorded where a reader will meet them rather than
here:

- **no test asserts the tier a route actually sits at.** Every route test installs an authentication
  provider of its own and the e2e suite always sends a token, so a route moved between the tiers
  would keep the suite green ([endpoint-auth](../api/endpoint-auth.md), quirks);
- **`/health` is the one route outside `konektRoutes`**, so anything reading that table to describe
  the server does not see it ([endpoint-health](../api/endpoint-health.md));
- **`HistoryScreen.pageUrl` spells an endpoint path in production code** outside a `*-shared-api`,
  beside the `@Resource` that already declares it ([endpoint-purchase](../api/endpoint-purchase.md)).

The generated OpenAPI document remains `B-23` and is deliberately not part of this.
