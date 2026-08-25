---
id: endpoint-health
title: Health
type: api_endpoints
status: active
services:
  - konekt-server
contract_source:
  - konekt:server Application.kt — `baseModule`, a literal path with no contract class
---

# API: health

> One route. It is here because the rule is that **every** route the server installs appears in this
> layer with its tier, and an operational route left out of that list is exactly the kind of route
> whose tier nobody ever states.

## Routes — all of them, no exceptions

| Method and path | Auth tier | Answers | Purpose |
|---|---|---|---|
| `GET /health` | **public** | `200`, `text/plain`, the body `ok` | tell a supervisor the process is answering |

**Its tier is a consequence of where it is installed, not of an entry anybody wrote.** It is
registered in `baseModule` (`server/src/main/kotlin/io/konekt/Application.kt`), which runs before
`configureAuthentication`, so it could not be inside `authenticate` even if someone wanted it there.
The tier is the right one — it exposes a two-letter string — but it is stated nowhere.

**It is the one route that is not in `konektRoutes`.** Every other route in the product sits in that
table with an `AuthTier` beside it; this one is mounted directly, one function earlier. Anything that
reads the table to describe the server — a generated document, a conformance walk — therefore does
not see it, and "the table covers everything" is true of the API surface and false of the server.

It is deliberately **not** `/api/...`: it is not part of the product's API surface, and it is the one
route with no `@Resource` behind it.

## Handlers (code anchors)

| Route | Handler |
|---|---|
| `GET /health` | `server/src/main/kotlin/io/konekt/Application.kt` — `baseModule`, inside `routing { }` |
| what calls it | `deploy/Dockerfile` — the container `HEALTHCHECK` |

## Request and response bodies

None, and `ok`. `ApplicationSmokeTest` asserts it.

## Errors

None it produces itself. A process that has stopped answering produces a connection failure rather
than a status code, which is the whole reason this route exists: the kernel accepts into the backlog
with no help from a hung process, so a TCP check would pass against a server that cannot serve.

## Quirks

- **The container healthcheck runs `bash`, and it had to be found the hard way.** `/bin/sh` in
  `eclipse-temurin:25-jre` is dash, which has no `/dev/tcp`; the check answered "Directory
  nonexistent" on every run and the container was permanently unhealthy while the process inside was
  serving. Nothing depended on it until `depends_on: service_healthy` did — an unhealthy container
  nobody waits for looks exactly like a healthy one.
- **The route is installed by `baseModule`, which a test can install without a database.** That is why
  it exists in that half of the split, and it is worth keeping: a health route that needs Postgres
  reports the database's health under the process's name.
