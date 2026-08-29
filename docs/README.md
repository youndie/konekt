# docs — konekt

A reference implementation of six Kotlin toolkits on the domain of an eSIM MVNO subscriber account.
Not a product an operator deploys: the telecom is the fixture that loads the stack honestly.
The documentation is layered; links run top to bottom.

```
[ Research (why the architecture is what it is) ]
                     │
[ Feature (business + BDD) ] ──▶ [ Client screen / flow ]
                                        │
                                        ▼
                              [ API endpoint (contract, auth tier) ]
                                        │
                                        ▼
                              [ Service (ownership, deploy) ]
```

| Layer | Directory | Answers | Source of truth |
|---|---|---|---|
| Research | `research/` | *why* it is built this way; what is verified, what is a hypothesis | the artefacts each fact names |
| Design | `design/` | what the interface commits the build to | the Claude Design canvas |
| Feature | `features/` | *what* the system does and *why*; BDD scenarios | this repository |
| Client | `screens/` | what the subscriber sees: states, actions, navigation | this repository + the screen's code |
| API | `api/` | URL, method, auth tier, where the contract lives | the shared modules |
| Service | `services/` | who owns the data, dependencies, deploy, local setup | this repository |

**All six directories now hold documents.** They were empty while there was no code, because a
feature document written ahead of an implementation documents intent as fact — the one thing this
format exists to prevent. That reason expired with the fourth feature vertical, and `B-39` filled
them in from the code that exists: four features, four screens, five endpoint documents and three
services.

Two rules govern what is in them, and they are worth stating here rather than once per file. **`main`
describes what exists** — anything not built yet stays in its backlog item. And **what was verified is
separated from what was assumed, explicitly**: every status code, error string, field name and limit
in these documents was read out of the source, and where something could not be established the
document says it does not cover it rather than guessing. A document that does not distinguish the two
is worse than no document, because both halves look equally authoritative.

**Backlog** — [backlog.md](../backlog.md): the goal, the stages and the generated index; the items
themselves are one file each in [`backlog/`](backlog/), cited as
`[B-03](backlog/B-03-component-dictionary.md)`.

## Conventions

- **`id`** in the frontmatter is unique and equals the filename.
- Cross-layer links are ids in the frontmatter and ordinary markdown links in the body.
- One document, one entity. A feature spanning the server and the client is **one** file.
- BDD scenarios are written from the code, not from memory: check the actual status codes and error
  strings before writing a scenario. While a scenario describes behaviour that does not exist yet, it
  is marked *target*.
- **The primary consumer is a coding agent.** Every document carries code anchors — paths to the
  module, the route, the renderer — so the reader reaches the code in one hop. Do not duplicate what
  lives in code; give the path. A copy rots, a path does not.
- Language: English, documents and code alike. Identifiers, URLs and HTTP header names verbatim as in
  the code.

## Templates

`templates/` holds a copy of the document templates, so the format travels with the repository.
Sections marked `<!-- optional -->` can be deleted.

## Checks

```bash
pip install pyyaml
make check
```

## Coverage map

The list below is **checked** against the files on disk: a document missing here, or an entry with no
file behind it, fails `coverage_map.py`. The grouping and the descriptions are written by a person —
the machine only guards the membership.

### Research (4)

- [x] [research-architecture](research/research-architecture.md) — verified facts about the six
  toolkits, the decisions taken, and the risks; the entry point
- [x] [research-stack](research/research-stack.md) — the versions read from the registries, the module
  layout, the layer rules, and the four types that exist before any feature does
- [x] [research-upstream-proposals](research/research-upstream-proposals.md) — five gaps found in the
  toolkits and the issues drafted for them
- [x] [source-draft](research/source-draft.md) — the original brief, in Russian, preserved verbatim
  and never edited: a draft rewritten to agree with its research stops recording what was believed
  first

Not in the map, because the checker guards only the five layer directories:
[design-app-canvas](design/design-app-canvas.md) — the interface design and the component dictionary
it commits konekt to.

### Services (4)

- [x] [konekt-server](services/konekt-server.md) — the Ktor process: every table, every screen, every
  route, the saga engine and the workers
- [x] [konekt-client](services/konekt-client.md) — the Compose Multiplatform renderer: the registry,
  the session, the transport and shape. JVM only, and that is upstream
- [x] [konekt-broker](services/konekt-broker.md) — the booblik instance the stand runs: three topics
  fixed at startup, no published port, no consumer offsets
- [x] [operator-boundaries](services/operator-boundaries.md) — per axis, whether a change is a
  variable, a server deploy, a client release or a broker restart, and which research section says so

### Features (5)

Identity:
- [x] [feature-authentication](features/feature-authentication.md) — number and one-time code, and
  sessions that can be ended before a JWT expires

Money:
- [x] [feature-plan-purchase](features/feature-plan-purchase.md) — the four-step saga with a
  confirmation, and a rollback stated in money rather than in apology

Line and allowance:
- [x] [feature-esim-install](features/feature-esim-install.md) — the four-step install flow, and the
  device limit it is built to be able to refuse for
- [x] [feature-usage-allowance](features/feature-usage-allowance.md) — what is left, how long it will
  last, and the live update that moves it on screen
- [x] [feature-roaming](features/feature-roaming.md) — a package bought at home that does nothing until
  it is used abroad, and is dated from that moment rather than from the purchase

### Screens / flows (4)

- [x] [screen-home](screens/screen-home.md) — balance and counter cards; the screen a live update
  changes one node of
- [x] [screen-esim-wizard](screens/screen-esim-wizard.md) — the four steps, the QR, and the refusal
  frame that keeps the meter on "1 of 4"
- [x] [screen-purchase-result](screens/screen-purchase-result.md) — the four ends of a purchase,
  including the compensated one
- [x] [screen-order-history](screens/screen-order-history.md) — everything that moved money, keyset
  paged, with reversals visible

### API (6)

- [x] [endpoint-auth](api/endpoint-auth.md) — five routes, three of them public on purpose, and one
  that exists only under a development flag
- [x] [endpoint-purchase](api/endpoint-purchase.md) — six routes: the order as data, and the two
  screens built from it
- [x] [endpoint-esim-wizard](api/endpoint-esim-wizard.md) — two routes, and a body that is an action
  rather than a DTO
- [x] [endpoint-home](api/endpoint-home.md) — the home screen and the SSE stream that keeps one card
  of it current
- [x] [endpoint-health](api/endpoint-health.md) — one route, and the only one that is not in the
  server's route table
- [x] [api-openapi](api/api-openapi.md) — the generated `openapi.json`: which half of an operation is
  derived from the routing tree and which half is declared and can therefore drift

The five endpoint documents and the generated document answer different questions and neither
replaces the other. A generator knows paths, methods and — since `B-23` — auth tiers, because the
tier is a value in the route table; it does not know why a tier is what it is, which refusals are
screens rather than status codes, or which path is spelled twice.
