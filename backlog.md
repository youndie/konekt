# Backlog: a white-label eSIM account that proves the stack carries it

> Role of this document: the product backlog. **One file per item in
> [`docs/backlog/`](docs/backlog/)** — `M-NN-<slug>.md`. What lives here is the index (generated) and
> everything that is not an item: the goal, the stages and the decisions.
>
> New item: copy [`docs/templates/backlog-item.md`](docs/templates/backlog-item.md), take the next
> free `M-NN`, and run `python3 scripts/backlog_index.py` after editing.

## Goal

konekt is a reference build, so "done" is not "the feature works" but "the feature works and what it
cost is visible". Two things are being demonstrated at once and they pull in different directions:
a subscriber account good enough that its screens are worth photographing, and six toolkits each
carrying the load it was written for on a domain that loads them without contrivance.

The order below follows that second goal. The wire comes first because in a backend-driven product
the component dictionary is the API and renaming a type later is a coordinated release of both sides.
Money comes second because compensation is the reason petich is here at all, and a saga that cannot
be made to fail on demand demonstrates nothing. Everything that makes the build *observable* comes
late on purpose — not because it matters less, but because three of the four things worth observing
do not exist until then.

## Stages

A stage is a field on the item, not a directory. Items are cited by id from documents in every layer,
so re-prioritising must never move a file.

| Stage id | Stage | What it is |
|---|---|---|
| `stage-m0-wire` | The wire and the shell | The dictionary, the design system, sign-in and one screen drawn end to end. Everything here is expensive to change later. |
| `stage-m1-money` | Money that can be undone | The purchase saga with its confirmation and its compensated branch, and the guards that stop the event chain from being silently absent. |
| `stage-m2-live` | Live | The broker, the outbox bridge, the realtime transport, and the eSIM wizard that gives them something to carry. |
| `stage-m3-product` | The rest of the product | Roaming, the custom builder, the tariff change, and the second brand. |
| `stage-m4-proof` | Proof | Conformance that cannot pass vacuously, observability in three tools, screenshots, and the gaps written down. |
| `stage-m5-upstream` | Upstream and the box | The findings filed where the next reader will look, and the boundaries stated where the buyer will read them. |

## Marks

`[ ]` open · `[~]` in progress · `[x]` done · `[?]` open question · `[-]` dropped

<!-- BEGIN INDEX -->

## Open (12)

| Task | | Priority | Size | Blocked by |
|---|---|---|---|---|
| [B-35](docs/backlog/B-35-e2e-compose-stand.md) `[~]` | An end-to-end stand on docker-compose, driven by one command in both places | P0 | M | B-14, B-15 |
| [B-22](docs/backlog/B-22-brand-b.md) `[~]` | Brand B: the colour kit ships from the server, the shape scale ships with the client | P1 | M | B-04, B-43 |
| [B-26](docs/backlog/B-26-observability-wiring.md) `[~]` | metrik, tracy and katcher wired, and a compose file that runs all three | P1 | M | B-08 |
| [B-27](docs/backlog/B-27-ios-crash-gap.md) `[~]` | Wire katcher into the iOS build, now that it has an Apple target | P1 | S | B-26, B-43 |
| [B-43](docs/backlog/B-43-client-composition-root.md) `[~]` | The client has every part of an application and no application | P1 | L | - |
| [B-19](docs/backlog/B-19-roaming.md) `[ ]` | Roaming: status, zones and packages bought before the trip | P2 | M | B-08 |
| [B-20](docs/backlog/B-20-custom-package-builder.md) `[ ]` | The custom package builder as a form, with the price coming from the server | P2 | M | B-08 |
| [B-21](docs/backlog/B-21-tariff-change.md) `[ ]` | Changing tariff, as a saga with a confirmation | P2 | M | B-08 |
| [B-25](docs/backlog/B-25-forward-compatibility-fixture.md) `[~]` | A route that sends a component the client does not know, on purpose | P2 | S | B-05 |
| [B-28](docs/backlog/B-28-screenshot-tests.md) `[~]` | Screenshot tests for the counter states and both brands | P2 | M | B-22, B-43 |
| [B-30](docs/backlog/B-30-operator-material.md) `[ ]` | Operator material: what is configuration and what is a release | P2 | S | B-22, B-27 |
| [B-41](docs/backlog/B-41-order-status-vocabulary-disagrees.md) `[ ]` | The server emits order statuses the component dictionary does not declare, and declares one nothing emits | P2 | S | - |

## Closed (31)

**The wire and the shell**

- [B-01](docs/backlog/B-01-build-skeleton-and-pinned-versions.md) `[x]` - Gradle skeleton: 9.7.1 on Java 25, convention plugins, ktlint, and six dependency lines pinned separately
- [B-02](docs/backlog/B-02-postgres-flyway-exposed.md) `[x]` - Postgres, Flyway and the Exposed plugin, including the tables petich does not create
- [B-03](docs/backlog/B-03-component-dictionary.md) `[x]` - Fix the component dictionary: nine own wire types in one KSP module
- [B-04](docs/backlog/B-04-design-system-surface-guard.md) `[x]` - Guard that the design system keeps its surface roles after the theme arrives
- [B-05](docs/backlog/B-05-unknown-component-renderer.md) `[x]` - An unknown component draws a block and reports itself
- [B-06](docs/backlog/B-06-otp-login.md) `[x]` - Number and OTP sign-in, written from scratch because kompot-auth is one action
- [B-07](docs/backlog/B-07-home-screen.md) `[x]` - Home: balance and counters, drawn from the server
- [B-31](docs/backlog/B-31-money-type.md) `[x]` - Money is a type, and only the server formats it
- [B-32](docs/backlog/B-32-testcontainers-harness.md) `[x]` - Repository tests run against a real Postgres, and use cases against MockK
- [B-33](docs/backlog/B-33-clock-as-a-dependency.md) `[x]` - Time is injected, because four different deadlines depend on it
- [B-34](docs/backlog/B-34-error-contract.md) `[x]` - One error contract: Result out of use cases, StatusPages into status codes
- [B-36](docs/backlog/B-36-zero-downtime-migrations.md) `[x]` - Expand and contract: a migration is compatible with the code already running
- [B-37](docs/backlog/B-37-ios-tests-run-nowhere.md) `[x]` - No iOS test is executed by anything, and the build says nothing about it
- [B-38](docs/backlog/B-38-refresh-and-logout.md) `[x]` - Refresh and logout: a token pair that can be ended

**Money that can be undone**

- [B-08](docs/backlog/B-08-purchase-saga.md) `[x]` - The purchase saga: four interceptors, with the confirmation as a suspend
- [B-09](docs/backlog/B-09-outbox-guard.md) `[x]` - Refuse to boot on a repository that silently drops events
- [B-10](docs/backlog/B-10-payment-mock.md) `[x]` - A payment mock that can refuse and can be slow
- [B-11](docs/backlog/B-11-rollback-screen.md) `[x]` - The rollback screen states the reversal in money, not in apology
- [B-12](docs/backlog/B-12-operation-history.md) `[x]` - Operation history, including the entries that did not happen

**Live**

- [B-13](docs/backlog/B-13-booblik-topics.md) `[x]` - booblik in the compose file, with its three topics declared at startup
- [B-14](docs/backlog/B-14-outbox-to-booblik-bridge.md) `[x]` - The bridge from the petich outbox to booblik
- [B-15](docs/backlog/B-15-sse-realtime.md) `[x]` - The realtime transport: an SSE endpoint and a client source
- [B-16](docs/backlog/B-16-traffic-simulator.md) `[x]` - The traffic simulator: a consumer that moves the counters
- [B-17](docs/backlog/B-17-esim-order-wizard.md) `[x]` - The eSIM order wizard, and an SM-DP+ mock that can be out of slots
- [B-18](docs/backlog/B-18-cache-versus-realtime.md) `[x]` - Answer in writing how the screen cache and a live update interact

**The rest of the product**

- [B-40](docs/backlog/B-40-no-way-to-add-money.md) `[x]` - A subscriber is created with nothing and there is no way to add any

**Proof**

- [B-23](docs/backlog/B-23-openapi-document.md) `[x]` - Publish an OpenAPI document, because the conformance kit reads one
- [B-24](docs/backlog/B-24-tck-in-ci-with-coverage-assertion.md) `[x]` - The TCK gate asserts what it visited, not that it was clean
- [B-39](docs/backlog/B-39-doc-layers-are-empty.md) `[x]` - The feature, screen and API layers are empty, and the reason they were empty has expired
- [B-42](docs/backlog/B-42-tests-that-cannot-run.md) `[x]` - A @Test whose return type is not void is silently ignored, and three of them were

**Upstream and the box**

- [B-29](docs/backlog/B-29-file-upstream-issues.md) `[x]` - File U1–U5 upstream and record what came back

<!-- END INDEX -->

## Decisions worth not re-litigating

**Nothing upstream is forked.** A gap in kompot, petich, booblik, katcher, metrik or tracy goes out as
an issue ([B-29](docs/backlog/B-29-file-upstream-issues.md)), konekt works around it in its own code,
and the workaround carries a comment naming the issue. The reason is not politeness: a second
implementation reading a published contract finds what the author cannot, and a fork moves that
finding into a private diff where it dies. See
[research-architecture](docs/research/research-architecture.md) D9.

**The order of the first three items is not arbitrary.**
[B-03](docs/backlog/B-03-component-dictionary.md) fixes the component dictionary before any screen
exists, and [B-04](docs/backlog/B-04-design-system-surface-guard.md) guards the design system
against the theme before any theme work. Both are cheap now and both are structural later — the dictionary
because it is the API, the wrapper because the defect it guards against is invisible once there is
enough styling to hide in.

**The technical stack is settled in `B-01`, not per feature.** Versions, the module layout, the layer
rules, the test harness and `Money` are all expressed in build files and in four types, and
[research-stack](docs/research/research-stack.md) records why each one is what it is. Two of those
decisions look like preferences and are not: any module touching Exposed is `kotlin("jvm")` because
`exposed-core` publishes no common metadata, and MockK is unavailable in any module that targets iOS
or Android because it publishes `common` and `jvm` and nothing else.

**Two guards are worth more than the features they guard.**
[B-09](docs/backlog/B-09-outbox-guard.md) and [B-24](docs/backlog/B-24-tck-in-ci-with-coverage-assertion.md)
both exist for the same reason: a green result that means nothing. petich drops events silently when
handed the wrong repository, and the conformance kit passes silently when it finds nothing to check.
Neither failure is visible from any assertion anyone naturally writes.
