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
| [katcher](https://github.com/youndie/katcher) | crashes, on the server and on Android |

One purchase runs through a kompot form, a petich saga, the outbox, booblik and back to an open
screen over the realtime channel — and is visible whole in tracy by its order id.

### 🚧 Status

**Research complete, no code.** What exists today is the documentation of what was read in those six
toolkits before anything was written:

- [docs/research/research-architecture.md](docs/research/research-architecture.md) — eleven verified
  facts with the artefact each was read in, ten decisions including four deviations from the original
  brief, six risks and three open questions;
- [docs/research/research-upstream-proposals.md](docs/research/research-upstream-proposals.md) — five
  gaps found in the toolkits, written as issues ready to file;
- [docs/design/design-app-canvas.md](docs/design/design-app-canvas.md) — the interface design and the
  component dictionary it commits the build to;
- [backlog.md](backlog.md) — thirty items across six stages.

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
