# Backlog: a reference implementation that shows what the stack costs

> Role of this document: the product backlog. **One file per item in
> [`docs/backlog/`](docs/backlog/)** — `M-NN-<slug>.md`. What lives here is the index (generated) and
> everything that is not an item: the goal, the stages and the decisions.
>
> New item: copy [`docs/templates/backlog-item.md`](docs/templates/backlog-item.md), take the next
> free `M-NN`, and run `python3 scripts/backlog_index.py` after editing.

## Goal

konekt is a reference implementation, so "done" is not "the feature works" but "the feature works and
what it cost is visible". Two things are being demonstrated at once and they pull in different
directions: a subscriber account good enough that its screens are worth photographing, and six
toolkits each carrying the load it was written for on a domain that loads them without contrivance.

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
| `stage-m5-upstream` | Upstream and the boundaries | The findings filed where the next reader will look, and the price of a rebrand stated where it will be read. |
| `stage-m6-reframe` | What this build claims to be | Every entry point described a product an operator buys, and what is here is a reference implementation on a telecom domain. The claim is the one thing no test checks, so it is corrected first — and the non-goals are written down beside it, because an absence with a reason and an absence without one look identical. |
| `stage-m7-completeness` | The reference, complete | The gaps that are gaps *of a reference*: a platform the multiplatform claim was never compiled for, verticals whose only user is an e2e test, and a demonstration chain welded to its own mock. Not the gaps of a product for sale — those are non-goals now. |

## Marks

`[ ]` open · `[~]` in progress · `[x]` done · `[?]` open question · `[-]` dropped

<!-- BEGIN INDEX -->

## Open (4)

| Task | | Priority | Size | Blocked by |
|---|---|---|---|---|
| [B-77](docs/backlog/B-77-the-stand-suite-decays-as-the-stand-ages.md) `[ ]` | Two stand scenarios fail on a stand left up for hours, and the hour-long soak was too short | P2 | S | - |
| [B-90](docs/backlog/B-90-the-ios-build-cannot-leave-the-simulator.md) `[ ]` | The iOS build runs only in a simulator: iosArm64 declares no binary and the .app is assembled by a shell script | P2 | M | - |
| [B-92](docs/backlog/B-92-the-sweeper-still-does-not-claim-a-saga.md) `[ ]` | Two sweepers still compensate the same abandoned saga; B-64 closed the money and left the race | P2 | S | - |
| [B-93](docs/backlog/B-93-two-verticals-have-screens-and-no-documents.md) `[ ]` | The tariff change and the custom package builder have screens and no documents in any layer | P2 | S | B-86, B-87 |

## Closed (89)

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
- [B-64](docs/backlog/B-64-a-rollback-refunds-once-per-replica.md) `[x]` - A purchase abandoned at the confirmation refunds once per running replica

**Live**

- [B-13](docs/backlog/B-13-booblik-topics.md) `[x]` - booblik in the compose file, with its three topics declared at startup
- [B-14](docs/backlog/B-14-outbox-to-booblik-bridge.md) `[x]` - The bridge from the petich outbox to booblik
- [B-15](docs/backlog/B-15-sse-realtime.md) `[x]` - The realtime transport: an SSE endpoint and a client source
- [B-16](docs/backlog/B-16-traffic-simulator.md) `[x]` - The traffic simulator: a consumer that moves the counters
- [B-17](docs/backlog/B-17-esim-order-wizard.md) `[x]` - The eSIM order wizard, and an SM-DP+ mock that can be out of slots
- [B-18](docs/backlog/B-18-cache-versus-realtime.md) `[x]` - Answer in writing how the screen cache and a live update interact
- [B-35](docs/backlog/B-35-e2e-compose-stand.md) `[x]` - An end-to-end stand on docker-compose, driven by one command in both places

**The rest of the product**

- [B-19](docs/backlog/B-19-roaming.md) `[x]` - Roaming: status, zones and packages bought before the trip
- [B-20](docs/backlog/B-20-custom-package-builder.md) `[x]` - The custom package builder as a form, with the price coming from the server
- [B-21](docs/backlog/B-21-tariff-change.md) `[x]` - Changing tariff, as a saga with a confirmation
- [B-22](docs/backlog/B-22-brand-b.md) `[x]` - Brand B: the colour kit ships from the server, the shape scale ships with the client
- [B-40](docs/backlog/B-40-no-way-to-add-money.md) `[x]` - A subscriber is created with nothing and there is no way to add any
- [B-43](docs/backlog/B-43-client-composition-root.md) `[x]` - The client has every part of an application and no application
- [B-45](docs/backlog/B-45-the-client-draws-one-screen-of-four.md) `[x]` - The client draws one screen of a product that has four
- [B-46](docs/backlog/B-46-no-login-screen.md) `[x]` - Both runners sign in through a route that must never ship
- [B-49](docs/backlog/B-49-the-app-has-no-shell.md) `[x]` - Four screens and no way between them except a banner
- [B-50](docs/backlog/B-50-login-frame-six.md) `[x]` - The login screen the canvas draws last is four additions, and two of them are not what they look like
- [B-51](docs/backlog/B-51-the-screens-against-the-canvas.md) `[x]` - Every screen photographed and held against the canvas, and what the two disagree about
- [B-52](docs/backlog/B-52-the-balance-is-not-a-card.md) `[x]` - The balance block is four texts in the screen's own column, and the canvas draws a card
- [B-53](docs/backlog/B-53-history-excludes-top-ups.md) `[x]` - History reads entitlements, so the top-up the button beside it starts will never appear
- [B-54](docs/backlog/B-54-the-esim-wizard-is-unreachable.md) `[x]` - The eSIM install wizard has routes, a step machine and no screen that leads to it
- [B-55](docs/backlog/B-55-home-header.md) `[x]` - The home screen has no header, and the two things a header names are not in the domain
- [B-57](docs/backlog/B-57-plans-catalogue-against-section-02.md) `[x]` - The catalogue has no filters, no per-unit price, one badge for every plan and no loading state
- [B-58](docs/backlog/B-58-orders-filters.md) `[x]` - Orders has no filters and an active order shows nothing about what is left of it
- [B-59](docs/backlog/B-59-the-confirmation-screen.md) `[x]` - The confirmation is a banner and a button; the canvas draws what is about to be spent
- [B-60](docs/backlog/B-60-counter-copy-and-grouping.md) `[x]` - The canvas states a counter as used-of-total and groups the three under the plan; we do neither

**Proof**

- [B-23](docs/backlog/B-23-openapi-document.md) `[x]` - Publish an OpenAPI document, because the conformance kit reads one
- [B-24](docs/backlog/B-24-tck-in-ci-with-coverage-assertion.md) `[x]` - The TCK gate asserts what it visited, not that it was clean
- [B-25](docs/backlog/B-25-forward-compatibility-fixture.md) `[x]` - A route that sends a component the client does not know, on purpose
- [B-26](docs/backlog/B-26-observability-wiring.md) `[x]` - metrik, tracy and katcher wired, and a compose file that runs all three
- [B-27](docs/backlog/B-27-ios-crash-gap.md) `[x]` - Wire katcher into the iOS build, now that it has an Apple target
- [B-28](docs/backlog/B-28-screenshot-tests.md) `[x]` - Screenshot tests for the counter states and both brands
- [B-39](docs/backlog/B-39-doc-layers-are-empty.md) `[x]` - The feature, screen and API layers are empty, and the reason they were empty has expired
- [B-41](docs/backlog/B-41-order-status-vocabulary-disagrees.md) `[x]` - The server emits order statuses the component dictionary does not declare, and declares one nothing emits
- [B-42](docs/backlog/B-42-tests-that-cannot-run.md) `[x]` - A @Test whose return type is not void is silently ignored, and three of them were
- [B-44](docs/backlog/B-44-undrawable-components-are-invisible.md) `[x]` - A component that decodes and cannot be drawn is invisible from every guard
- [B-56](docs/backlog/B-56-unreachable-screen-guard.md) `[x]` - Nothing fails when a screen the server serves is the destination of no action anywhere
- [B-61](docs/backlog/B-61-doc-says-two-renderers-of-nine.md) `[x]` - The design document says the client renders two of the nine components; it renders all nine
- [B-62](docs/backlog/B-62-the-stand-shared-ports-with-the-machine.md) `[x]` - Three tests accused the services that were working; a local daemon held their ports
- [B-63](docs/backlog/B-63-four-copies-of-one-walk.md) `[x]` - Five hand-kept lists of which components nest, and each goes stale by looking at less
- [B-65](docs/backlog/B-65-an-edited-migration-rolls-the-deploy-back.md) `[x]` - Editing a comment in a deployed migration rolled the deploy back, and nothing before the contour noticed
- [B-66](docs/backlog/B-66-the-esim-qr-is-unreachable-through-the-app.md) `[x]` - The activation code cannot be reached from the app: the resume path never carries the profile
- [B-67](docs/backlog/B-67-the-top-up-field-is-off-by-a-hundred.md) `[x]` - The top-up field reads minor units while every number printed beside it is major
- [B-68](docs/backlog/B-68-a-refused-purchase-never-says-why.md) `[x]` - Five reasons a purchase is refused render as one sentence that names none of them
- [B-69](docs/backlog/B-69-held-is-not-installed.md) `[x]` - "1 eSIM installed" over a number that counts profiles held, installed or not
- [B-70](docs/backlog/B-70-one-currency-written-two-ways-on-one-screen.md) `[x]` - The amount field writes the currency as a suffix while every figure beside it is a prefix
- [B-71](docs/backlog/B-71-two-primary-buttons-on-the-completed-purchase.md) `[x]` - The way out is drawn with the same weight as the action, so the completed purchase has two primaries
- [B-72](docs/backlog/B-72-orders-is-the-only-tab-without-a-title.md) `[x]` - Orders is the only tab that opens with no title
- [B-73](docs/backlog/B-73-the-stand-registered-no-actions.md) `[x]` - The stand's Json registered none of the three action modules, so every action it read was unknown
- [B-74](docs/backlog/B-74-the-qr-fills-the-screen-and-buries-what-to-do-with-it.md) `[x]` - The activation code has no maximum size, so it grows with the window until the controls leave the screen
- [B-75](docs/backlog/B-75-the-scroll-survives-a-wizard-step.md) `[x]` - A wizard step keeps the previous step's scroll, so the new screen opens part-way down
- [B-76](docs/backlog/B-76-done-returns-to-the-first-step-instead-of-leaving.md) `[x]` - Done finishes the install and lands the subscriber back on step one of a new one
- [B-78](docs/backlog/B-78-one-line-one-esim.md) `[x]` - Every completed install mints another eSIM, and every purchase offers to do it again

**Upstream and the boundaries**

- [B-29](docs/backlog/B-29-file-upstream-issues.md) `[x]` - File U1–U5 upstream and record what came back
- [B-30](docs/backlog/B-30-operator-material.md) `[x]` - Operator material: what is configuration and what is a release
- [B-47](docs/backlog/B-47-first-release-tag.md) `[x]` - Nothing has ever been released, so three checks stand in for the one that matters
- [B-48](docs/backlog/B-48-deployed-instance.md) `[x]` - Everything that runs this product is a file on somebody's laptop

**What this build claims to be**

- [B-79](docs/backlog/B-79-the-repository-calls-itself-a-box.md) `[x]` - The repository calls itself a box an operator buys, and it is a reference implementation on a telecom domain
- [B-80](docs/backlog/B-80-the-non-goals-are-nowhere.md) `[x]` - Nothing states what this build is deliberately not, so every absence reads as unfinished work
- [B-81](docs/backlog/B-81-the-boundaries-table-has-no-row-for-language.md) `[x]` - The boundaries table has no row for language, currency, date format, time zone or the app's own name
- [B-82](docs/backlog/B-82-the-brand-kit-document-says-the-theme-is-unwired.md) `[x]` - The brand-kit document says no theme is served over HTTP, and the server has been serving one since B-22
- [B-83](docs/backlog/B-83-typography-does-not-ship-from-the-server.md) `[x]` - Two documents promise typography from the server; no kit carries any and a font family cannot cross the wire
- [B-84](docs/backlog/B-84-a-guard-four-comments-cite-does-not-exist.md) `[x]` - Four files name DevRoutesAreNotProductionTest as what keeps the dev routes out of a real build, and there is no such test

**The reference, complete**

- [B-85](docs/backlog/B-85-the-client-has-no-android-target.md) `[x]` - The client claims Compose Multiplatform on two platforms and declares no Android target at all
- [B-86](docs/backlog/B-86-changing-tariff-has-no-screen.md) `[x]` - Changing tariff has a saga, a table, a confirmation and no screen: only an e2e test can reach it
- [B-87](docs/backlog/B-87-the-custom-package-cannot-be-bought.md) `[x]` - The custom package form prices a package and cannot sell one, and nothing in the app leads to it
- [B-88](docs/backlog/B-88-roaming-starts-through-a-dev-route.md) `[x]` - Roaming has no screen of its own, and the only way to start a package is a public dev route that names the subscriber in a query
- [B-89](docs/backlog/B-89-the-usage-consumer-only-runs-with-the-simulator.md) `[x]` - The only consumer of the usage topic is constructed inside the simulator's own starter, so reading real usage means also inventing some
- [B-91](docs/backlog/B-91-a-second-replica-loses-live-updates.md) `[x]` - A second replica silently loses live updates, and the only guard in the chart is about the simulator

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
