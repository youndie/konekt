# Checkpoint and restore (CRaC) instead of a cold start — 2026-09-11

`scripts/measure/crac-restore.sh <phase>` on the build box (Ubuntu 24.04 in WSL2, Core Ultra 7
255HX, Docker 29.1.3), the base compose file's Postgres, broker and migrations under the project
name `konekt-crac`, and the server as a container of `konekt-server:crac` on that network — the
installed distribution on `azul/zulu-openjdk:25-jre-crac` (Zulu 25.36+205-CRaC-CA, 25.0.4.1),
because Azul is the only vendor shipping a CRaC JDK for 25 and its JRE image carries `jcmd`.

This is the gate of zavarnik's third phase (its `B-32`); the reasoning lives in that repository's
`docs/research/research-crac.md`. **No CPU limit here** — the numbers below compare two ways of
starting the same image on one box in one session, and not with §6 or §6a of
`research-measurements.md`, which ran under the chart's one core.

| File | What |
|---|---|
| `h1-no-policy.log` | checkpoint with nothing configured: every socket named, with its creator thread |
| `h2-listening-reopen.log` | only the listening socket handled — the pool's ten are what is left |
| `h3-sockets-closed.log` | `action: close` on the pool's sockets: Hikari reopens them under the checkpoint's feet |
| `h7-sockets-ignored.log` | `action: ignore`: the checkpoint succeeds and the restore serves |
| `h9-round-trip.log` | the full round trip — top-up, purchase, confirm, realtime — restored against a plain start |
| `h9-round-trip-no-simulator.log` | the same run with the traffic simulator off: **both** sides see no updates, which is what made the first reading of it wrong |
| `h10-ten-against-ten.log`, `coldstart.csv` | ten plain starts against ten restores, alternating |
| `h4-postgres-restarted.log` | Postgres restarted between checkpoint and restore |
| `h5-environment.log`, `h5-brand-control.log` | restore with a different `BRAND` in the container's environment, and what a plain start does with the same flag |
| `h6-two-restores.log` | two restores of one image, two different subscribers, into the eSIM wizard |

## The numbers (`h10`, medians of ten)

| | plain start | restore |
|---|---|---|
| `docker run` → first 200 on `/health` | 2 317 ms (2 141–2 513) | **131 ms** (115–151) |
| first signed-in home screen after that | 118 ms (102–128) | **32 ms** (27–50) |
| checkpoint image | — | 143 MB |

## What the phases found

- **The pool is what refuses the checkpoint, and it says so by name.** With
  `-Djdk.crac.collect-fd-stacktraces=true` every open socket carries its creator:
  `This file descriptor was created by HikariPool-1:connection-adder`. Ten of those, one to the
  broker, the listening `ServerSocketChannelImpl` from a dispatcher thread, and the selector's
  epoll descriptors.
- **Closing them by policy does not work.** `action: close` closes the connections, Hikari
  notices at once (`Failed to validate connection … This connection has been closed`) and its
  `connection-adder` opens new ones — the checkpoint then fails on a socket that did not exist
  when it started (`FD fd=133 type=socket path=socket:[…],port=0`).
- **Ignoring them does.** `action: ignore` lets the checkpoint through; on restore warp reports
  `Can't open FD … - replacing FD with /dev/null` for each, Hikari validates every connection it
  holds, discards all ten and opens new ones, and the broker client reconnects itself
  (`reconnected to the broker at broker:9092 — generation 1`). **No change to the application.**
- **The round trip works.** Restored, the probe signs in, tops up, buys a plan, confirms it, sees
  the order `completed` and receives 12 realtime updates over SSE — the same 12 the plain start
  receives. The realtime path is what proves the broker, and it only says anything with
  `SIMULATE_TRAFFIC=true`: with the simulator off both sides see zero, which is a fact about the
  probe and not about the restore.
- **A restarted database is survivable.** Postgres restarted between checkpoint and restore:
  ready in 124 ms, sign-in and screens 200.
- **The environment of the restore container does not reach configuration read at startup.**
  `BRAND=brand-b` on the restored container still answered with brand-a's name. The control says
  the flag works on a plain start of the same image: brand-a answers `Konekt`, brand-b answers
  `Inkline`. So the snapshot carries the configuration it was taken with, and anything read once
  at boot — a brand, a database URL, a secret — is frozen into it.
- **The eSIM identifiers do not repeat, and the reason is worth knowing.** The wizard issues its
  activation code from `kotlin.random.Random.Default` — on the JVM, the calling thread's
  `ThreadLocalRandom` — which a restore does not reseed. Five restores of one snapshot were walked
  to the issuing step, two with the traffic simulator on and three with it off
  (`SIM=false`): five different codes. The stream is genuinely shared between replicas — zavarnik's
  `experiments/crac-smoke/randoms.sh` shows two restores drawing identical `ThreadLocalRandom`
  values — but in a server the sign-in, the token, the screens, Exposed and Hikari draw from it
  first, on whichever pool thread served the request, so how far along the stream an identifier
  lands is not deterministic. A collision is possible, not reproducible on demand; the check that
  would catch it has to probe the generators, not compare the product's answers.
