# docs — konekt

A white-label subscriber account for an eSIM MVNO, built as a reference for six Kotlin toolkits.
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

**Four of those six directories are empty, and that is the current state rather than an oversight.**
There is no code yet. `main` describes what exists, so a feature document written now would be
documenting intent as fact — the one thing this format exists to prevent. `features/`, `screens/`,
`api/` and `services/` fill in as the milestones close, each from the code that closed them.

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

### Services (0)

None yet. Fills in with `B-01`.

### Features (0)

None yet. The first is authentication, with `B-06`.

### Screens / flows (0)

None yet. The first is home, with `B-07`.

### API (0)

None yet. The OpenAPI document arrives with `B-23`.
