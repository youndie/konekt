# konekt

**a reference implementation of six Kotlin toolkits, on the domain of an eSIM MVNO subscriber
account** — not a product an operator can deploy and sell service on. The telecom is the fixture; what
the stack costs to carry it is the subject.

> 📱 a new screen ships from the server; a new *kind* of screen ships with the client

<p align="center">
  <img src="docs/screenshots/brand-a-home.png" width="180" alt="Home — brand A">
  <img src="docs/screenshots/brand-a-plan-detail.png" width="180" alt="Plan detail — brand A">
  <img src="docs/screenshots/brand-a-esim-scan.png" width="180" alt="eSIM install, the QR step — brand A">
  <img src="docs/screenshots/brand-b-home-dark.png" width="180" alt="Home — brand B in the dark theme, the same markup on a second kit">
</p>
<p align="center"><sub>The desktop client at phone size, on the recorded screens the goldens use, in the product's own typefaces. The fourth frame is the second brand kit in the dark theme — the same trees, a different server response. More in <a href="docs/screenshots/">docs/screenshots</a>, both themes.</sub></p>

### 🤔 What it is

The product is real enough to photograph — balance and quotas, plans and packages, ordering and
installing an eSIM, roaming, history — and everything outside its boundary is mocked on purpose: the
billing system, the SM-DP+ that issues eSIM profiles, the payment gateway, the SMSC that would carry
an OTP. There is also no management surface, no catalogue anyone edits without a deploy, no seam
under the billing and no second tenant — and none of those is a to-do. A domain that loads the
toolkits honestly is the whole requirement; building past it would produce a worse version of a
product several vendors already sell. Each absence, with its reason and with what would end it, is
[reference-scope](docs/services/reference-scope.md).

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

Two things are written down beside the code, and they are the reason to read this rather than a
sample application. **What each toolkit costs**: 17 database writes for a six-interceptor saga, a
client release for a corner radius, a broker restart for a topic. And **the failures a green build
does not show**, each with the guard that now catches it: petich dropping outbox events silently, a
conformance kit passing vacuously, an Apple target nobody published.

### 🚧 Status

**Built.** The server, the saga, the broker bridge, the realtime channel and the eSIM wizard are in
and driven end to end by a docker-compose stand, and every published tag is pulled back by CI, stood
up and driven again — so the image that exists is the image that was walked. The Compose client runs
on the desktop, on an Android device and in the iOS simulator, and draws every screen the way the
canvas draws it — matched screen by screen against the real frames, with the differences kept on
purpose written down ([B-114](docs/backlog/B-114-the-client-does-not-look-like-the-canvas.md),
[B-115](docs/backlog/B-115-the-esim-install-flow-does-not-look-like-the-canvas.md)).

**Deploying is a command somebody types.** The Helm chart is real and refuses five ways, `make deploy`
uses `--reset-then-reuse-values` and runs its own check against the cluster afterwards — and nothing
automatic does either: no workflow deploys, no contour is running this build today, and the one check
that stands two versions side by side is run by nobody
([B-119](docs/backlog/B-119-the-rolling-check-belongs-with-a-release-and-the-release-does-not-run-it.md)).

**What is open.** Of the product, presentation only: the purchase confirmation as a sheet over the
plan page ([B-116](docs/backlog/B-116-the-confirmation-is-a-screen-not-a-sheet.md)). Of what this
repository is *for* — knowing what the stack costs — the soak and the cost paragraph below
([B-117](docs/backlog/B-117-what-the-stack-costs-measured-under-load-and-over-time.md)), plus two
guards that do not guard ([B-119](docs/backlog/B-119-the-rolling-check-belongs-with-a-release-and-the-release-does-not-run-it.md),
[B-120](docs/backlog/B-120-the-bdd-report-does-not-check-the-tests-it-names.md)). Everything else is
in [backlog.md](backlog.md), item by item, with the reason attached to each — and the ones stopped on
a stated cause say what the cause is rather than sitting in a list of the unfinished.

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
product is silent — and this one was silent in three different places for three different reasons, none
of which a local test could have found.

| | Server (JVM) | client (iOS) | client (Android) | client (desktop) |
|---|---|---|---|---|
| crashes (katcher) | **delivered** — a route that throws is reported and shows in katcher | **delivered** — a simulator crash arrives naming its release | **delivered** — a crash on a Pixel 6a arrives naming its release, since katcher `0.6.41` | breadcrumbs only |
| logs and traces (tracy) | **delivered** — a purchase is findable by `orderId` | **delivered** — a screen the client cannot draw is findable by wire type | same as iOS | same as iOS |
| latency and errors (metrik) | **delivered** — latency per route | — | — | — |

**"Delivered" means measured at the collector, not at the agent's configuration.** All three
libraries answer a missing key, a wrong endpoint or an unreachable collector by doing nothing, so from
inside, a deployment that meant to be observed and is silent looks exactly like one that works. Every
row above was earned by driving the product and finding the result at the far end.

**Four of the rows were red first, each for a different reason.** tracy published no Apple target until
[tracy#16](https://github.com/youndie/tracy/issues/16), and katcher none until
[katcher#25](https://github.com/youndie/katcher/issues/25). The server's katcher was configured
correctly and could not receive anything, because `StatusPages` catches every route exception before an
uncaught-exception handler runs — so the handler now reports from inside `StatusPages`. And Android
lost its report at the last step: a deliberate crash on a Pixel 6a fired the hook and reached katcher's
handler, but the multiplatform client had no android variant, the build resolved the JVM one, and its
cache lived at `user.dir` — `/` on Android, unwritable, and a property the platform refuses to let an
application change. Filed as [katcher#27](https://github.com/youndie/katcher/issues/27), closed in
`client:0.6.41` with an android variant that caches in the application's own `cacheDir` and a `start`
that refuses when it cannot store a report.

**The Android fix was measured by the same harness that measured the failure**, and the harness had
to learn one more thing on the way: katcher uploads on the *next* `start`, from a background worker,
and a process that throws two milliseconds after starting is gone before the upload leaves. A crash
proves storage; delivery is proved by a second launch that does not crash, which is why
`CrashActivity` has a `KONEKT_CRASH=false` mode. Same device, same launch, and the report is on the
collector under `android-katcher-0.6.41`.

The one blank left is metrik on the client, which measures route latency and has no routes there.

### 📏 What it costs, measured

Measured on 2026-09-02 on a rented stand, not estimated, against
`ghcr.io/youndie/konekt-server:v0.1.40` — the tag names the commit, and every figure here is that
binary's rather than this tree's: the server in its chart's limits — **one CPU, 1 GiB** — on a
2 vCPU / 3.8 GiB Hetzner box (Ubuntu 26.04) beside Postgres 18, booblik and the three collectors; k6 on a second identical box over a private link (RTT ≈ 0.9 ms); three
rounds per point, warm-up excluded, the collector on the stand as the oracle with the generator's
own timings beside it. Every figure below has its window, its spread and its caveats in
[research-measurements](docs/research/research-measurements.md); the raw record sits beside it.

| | on one core |
|---|---|
| a screen (`home`, `plans`, plan detail) | p50 2–4 ms, p95 under 7 ms up to **400 requests a second**; the knee at **≈800**, where the server's CPU is the limit |
| a purchase (the saga end to end) | p50 9 ms to start, 13 ms to confirm, p95 ≈20 ms, flat to **40 a second**; the knee between **80 and 160**, where Postgres is the limit — one purchase costs about ten screens |
| fifty concurrent purchases on one account | the twenty the money allowed, the rest refused; **no balance below zero, no double capture** — at a quarter of a second each, the price of the row lock |
| the usage consumer (broker → counter → push) | **≈500 events a second**; the SSE channel held 1 000 streams, the consumer was the wall past ~800 lines at three events per line per five seconds |
| a broker restart under load | the producer back in a second, the consumer in three ticks, counters monotonic |
| being observed (tracy + metrik) | **under the noise** — less than the spread between two warm rounds at 400 rps |
| a cold start | 5–7 s to health; the first request 0.3–0.9 s; the next hundred five times the warm median |
| the wire | the heaviest screen 4.3 KB plain, under 1 KB gzipped |
| twelve hours at a steady rate | *running since 2026-09-03; its slopes land here when it ends. The first attempt is void and says why: it held one fifteen-minute token for twelve hours and measured the 401 path — [the record](docs/research/measurements-2026-09-02/soak/README.md)* |

What the session also found, and the report keeps: the traffic simulator ticks every subscriber
every five seconds, so a stand that signs in fifty thousand of them is a stand whose simulator is
the load — the later rounds' tails carry it, and the report says which.

### 🎨 The rebrand, as a demonstrated property

A second brand's palette ships from the server and the client applies it without a rebuild — that
much is built and photographed. It is worth stating precisely what it covers, because the boundary is
not where it is usually assumed to be:

| Axis | Ships as |
|---|---|
| colours and every string | a server response — **no client rebuild**; a new kit is a server deploy |
| the type scale — sizes, weights, letter spacing | the same way, and no kit here carries one yet — the client's own scale is the canvas's |
| the font family | **a client release** — Manrope and Space Grotesk ship inside the client as static instances; the wire's text style has no family, so a face a server named would not arrive |
| screens, layouts, flows | a server response — no rebuild |
| the shape scale (corner radii), and how tall a control is | **a client release** — the wire has no vocabulary for shape or size, deliberately |
| a new kind of component | **a client release** — the dictionary is the API |
| icons in the interface | a server deploy — an icon travels as path data on a 24-grid and the client colours it; what an unknown icon degrades to is written down per control |
| a new event topic | a broker restart — booblik fixes its topics at startup |

The reasoning behind each row is in the research, §1.2, §1.5 and §1.8. The full version — what is a
variable, what is a deploy, what is a release an operator does not control — is
[operator-boundaries](docs/services/operator-boundaries.md).

### 📄 License

MIT.
