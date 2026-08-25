# konekt

**a white-label subscriber account for an eSIM operator** — the operator rebrands the box and gets a
phone application and a server without writing a client

> 📱 a new screen ships from the server; a new *kind* of screen ships with the client

### 🤔 What it is

konekt is a reference build. The product is real enough to photograph — balance and quotas, plans and
packages, ordering and installing an eSIM, roaming, history — and everything outside its boundary is
mocked on purpose: the billing system, the SM-DP+ that issues eSIM profiles, the payment gateway, the
SMSC that would carry an OTP.

What is not mocked is the wiring. Six Kotlin toolkits each carry the load they were written for, on a
domain that loads them without contrivance:

| | |
|---|---|
| [kompot](https://github.com/youndie/kompot) | the whole UI contract: the server describes a screen, the client renders it |
| [petich](https://github.com/youndie/petich) | every operation that moves money, with compensation and a confirmation that expires |
| [booblik](https://github.com/youndie/booblik) | the event bus between the server and its consumers |
| [tracy](https://github.com/youndie/tracy) | structured logs and traces, keyed by `msisdn`, `iccid` and `orderId` |
| [metrik](https://github.com/youndie/metrik) | latency, errors and deploy markers |
| [katcher](https://github.com/youndie/katcher) | crashes — wired into the iOS client today, and see the platform table below |

One purchase runs through a kompot form, a petich saga, the outbox, booblik and back to an open
screen over the realtime channel — and is visible whole in tracy by its order id.

### 🚧 Status

**Building.** The server, the saga, the broker bridge, the realtime channel and the eSIM wizard are
in and driven end to end by a docker-compose stand; the Compose client is a library with no
application around it yet. What is done and what is not is [backlog.md](backlog.md), item by item,
with the reason attached to each — including the three that are stopped on a stated cause rather than
merely unfinished.

- [docs/research/research-architecture.md](docs/research/research-architecture.md) — what was read in
  the six toolkits, each fact with the artefact it was read in, and the decisions and deviations that
  followed;
- [docs/research/research-upstream-proposals.md](docs/research/research-upstream-proposals.md) — the
  gaps found in them, filed as issues, with what came back;
- [docs/design/design-app-canvas.md](docs/design/design-app-canvas.md) — the interface design and the
  component dictionary it commits the build to;
- [docs/features/](docs/features), [docs/screens/](docs/screens), [docs/api/](docs/api) and
  [docs/services/](docs/services) — the layers, written from the code rather than from intent.

### 👁 What is observed, and on which platform

Stated as a table because "we have crash reporting" is the sentence that hides which half of a
product is silent — and this one was silent on iOS for a reason no amount of local code could fix:
katcher published no Apple target until [katcher#25](https://github.com/youndie/katcher/issues/25)
closed it.

| | Server (JVM) | Compose client (iOS) | Compose client (desktop) |
|---|---|---|---|
| crashes (katcher) | not wired yet — `B-26` | **wired**, uncaught and caught | not wired |
| logs and traces (tracy) | not wired yet — `B-26` | — | — |
| latency and errors (metrik) | not wired yet — `B-26` | — | — |

The iOS row is the only one that says "wired" and it stops short of the whole claim: what is proved
is that the reporter refuses to start half-configured and that it links and runs on a real Apple
target. A crash travelling from a running application to a katcher deployment needs both of those to
exist, and `B-27` says which items they are.

### 🎨 What "white-label" actually covers

Worth stating precisely, because the boundary is not where it is usually assumed to be:

| Axis | Ships as |
|---|---|
| colours, typography, every string | a server response — no rebuild |
| screens, layouts, flows | a server response — no rebuild |
| the shape scale (corner radii) | **a client release** — the wire has no vocabulary for shape, deliberately |
| a new kind of component | **a client release** — the dictionary is the API |
| a new event topic | a broker restart — booblik fixes its topics at startup |

The reasoning behind each row is in the research, §1.2, §1.5 and §1.8.

### 📄 License

MIT.
