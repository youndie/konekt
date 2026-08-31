---
id: reference-scope
title: What this build is deliberately not
type: service
status: active
repo_url: https://github.com/youndie/konekt
# Every module, because a boundary is drawn around the whole build and not around a process.
module: server, client, broker
tech_stack: [Kotlin/JVM 25, Compose Multiplatform, Ktor, booblik]
owner: unassigned
tags: [scope, boundaries, non-goals]
---

# What this build is deliberately not

konekt is a reference implementation of six toolkits on the domain of an eSIM MVNO subscriber
account ([B-79](../backlog/B-79-the-repository-calls-itself-a-box.md)). The telecom is the fixture.
So a number of things a real operator's product has are absent here **on purpose**, and this document
is where each one says so.

It exists because *an absence with a reason and an absence without one look identical*. A reader who
finds no admin surface cannot otherwise tell a decision from a to-do, and the backlog does not settle
it either: 79 items closed and none of them says "no management surface, on purpose".

This is not [operator-boundaries](operator-boundaries.md), which answers a different question —
*what does a change cost* — and is a price list of things that can be bought. This one is the list of
things that are not for sale.

## How to read a row

Each non-goal carries two things, and a row missing either is not a boundary:

- **Why** — without a reason it is indistinguishable from an oversight;
- **What would end it** — without the shape of the change it is indistinguishable from a refusal.
  These are not estimates and not promises; they say where the work would land.

## The list

| Not here | Why | What would end it |
|---|---|---|
| **A management surface** — no admin route, no management API, no CLI. A subscriber row is created by their first successful sign-in | Nobody operates this instance. An admin surface has a second auth tier, a second set of screens and a second threat model, and none of the six toolkits is exercised any harder for having it | A route group at a role tier, its own screens, and `DevRoutesAreNotProductionTest`'s sibling for it. The auth tiers already distinguish role from user ([endpoint-auth](../api/endpoint-auth.md)) |
| **The catalogue as data** — four plans in `StaticPlanCatalog`, three tariffs in `TariffData.kt`, both Kotlin `val`s | The BSS is outside the boundary, so there is nothing real to ask. The file says it: *a table with a seed migration would look more finished and would be the same fiction with a schema around it* | A table, a migration and a repository behind the existing `PlanCatalog` interface — the seam is already there, and the mock is what is behind it |
| **A seam under the billing** — balance, ledger and usage counters are konekt's own Postgres tables | An MVNO's OCS owns those. Modelling one would mean either mocking an OCS (a second fiction under the first) or inventing a protocol nobody publishes | A port beside `PaymentGateway` with the same shape, and the ledger becoming a cache of somebody else's truth — which changes what a compensation means |
| **A real SM-DP+ contract** — `SmDpPlus` has two methods, `capacityFor` and `issue` | GSMA's ES2+ has an order lifecycle, an EID, a download counter and a notification that says a profile was installed. Here a button says it. The two methods are the ones the *flow* needs, and the refusal is modelled because the canvas draws it | The ES2+ order states on the interface, an EID from the device, and the install confirmation arriving as an event rather than as a tap |
| **A real PSP** — `PaymentGateway.settle` is one synchronous call | A real provider has capture, void, refund, retries and 3-DS, and 3-DS alone is a redirect out of the application and back. The one call is what makes the decline branch reachable on demand, which is the branch the saga exists to show | Capture and refund as separate steps in the saga, an idempotency key per attempt, and a screen for the interrupted 3-DS return |
| **A real SMSC** — `OtpDelivery` is a `fun interface`, is not `suspend`, and returns nothing | The boundary of this system stops at the SMSC. A delivery that cannot fail and cannot be awaited is honest about being a mock; one that pretended to retry would be fiction with a queue around it | `suspend`, a result, and a delivery status the OTP screen can show. The auth flow would then have a state it does not have today |
| **Recurring billing** — no scheduler, no billing period, no invoice, no monthly charge of any kind. The only thing in the build that resembles a period is `BillingBoundary.nextAfter`, and nothing crosses it with money | A recurring charge is a scheduler, proration, a dunning path and a failure mode per cycle, and it demonstrates **none** of the six toolkits this build is about — the sagas already show a confirmation and a compensation on a purchase that happens once. It was not a decision until [B-102](../backlog/B-102-the-profile-states-a-tariff-nothing-bills.md): the profile named a tariff priced at *$5 / month* that nothing had ever charged for, which is an absence that reads as a bug because the product claimed otherwise | A scheduled charge per subscriber against the boundary the tariff saga already computes, a ledger entry per cycle, and a refusal path for the cycle that cannot be paid. The tariff change saga is the half that exists |
| **Multi-tenancy** — no tenant column in any migration | One brand per deployment is what the rebrand demonstration needs, and `BRAND` already picks among the kits an image carries. A tenant column touches every table, every query and every index in the build | A tenant on every table and in every unique index, a resolver at the edge, and the theme becoming per-request rather than per-process |
| **Localisation** — no `stringResource`, no `Accept-Language`, no bundles. Every string is an English literal in the server's Kotlin | The server composes every screen, so language is a server-side concern and one audience per deployment is the stated assumption — `MoneyFormat` says so for currency and `DayFormat` pins `Locale.ENGLISH` and `ZoneId.of("UTC")` | A locale on the request, a bundle per language on the server, and formatting that stops being a constant. The client needs no change, which is the point of the wire |
| **Presence in an app store** — no signing, no icon, no store metadata | Everything an app review needs exercises none of the six toolkits. Android *runs* — [B-85](../backlog/B-85-the-client-has-no-android-target.md) put a build on a physical Pixel — and shipping is a different job | Everything an app review needs. This is the row most likely to stay here permanently |
| **Running the iOS build on a physical device** | **No Apple account, and there will not be one.** Installing on a phone needs a development team; the simulator needs none. So every Apple statement this build makes is true of a simulator: the screens are drawn there, and `B-27`'s katcher crash is a Mach-O process on macOS — which says the reporter links and posts, not that a crash from an arm64 phone arrives. [B-90](../backlog/B-90-the-ios-build-cannot-leave-the-simulator.md) is closed as this boundary rather than as work | An Apple ID with a development team, and an install through `xcrun devicectl`. The code is ready for it: `iosArm64` links `KonektHome` and `KonektCrash` as arm64 executables, and the bundle carries the launch screen and scene manifest a device needs |
| **More than one server replica** — the realtime bus is `KompotUpdateBroadcaster`, in memory, and the sweeper runs per replica | One instance is the honest configuration for a reference. A shared bus is a dependency and an operational surface; kompot's own reasoning applies, an update is losable because the next screen fetch carries current state | `kompot-realtime-redis` and a claim on the sweeper. The boundary is enforced rather than implicit: [B-91](../backlog/B-91-a-second-replica-loses-live-updates.md) made `charts/konekt/templates/server.yaml` refuse any `replicas > 1`, and `scripts/chart-check.sh` proves each refusal names its own reason |

## What is *not* on this list, and why

Three things look like they belong here and do not:

- **Android.** It was work rather than a boundary, and it is done:
  [B-85](../backlog/B-85-the-client-has-no-android-target.md) put a build on a physical Pixel, signed
  in against a stand and drew the home screen. The iOS device build went the other way and is a
  boundary now, in the table above.
- **The custom package builder**, which existed on the server and nowhere else. A vertical whose only
  user is an e2e test was unfinished rather than scoped out, and it is finished now: its screens, its
  destinations and its e2e scenario. [B-87](../backlog/B-87-the-custom-package-cannot-be-bought.md).
  The tariff change went the same way in
  [B-86](../backlog/B-86-changing-tariff-has-no-screen.md) and then **back**: those screens priced
  something that is never charged, so [B-102](../backlog/B-102-the-profile-states-a-tariff-nothing-bills.md)
  removed them and the row above is the boundary that replaced them. The saga is still driven end to
  end — over its DTO routes, by `TariffChangeScenarioTest`.
- **Alerting thresholds** ([B-26](../backlog/B-26-observability-wiring.md)). Not a decision: tuning
  them needs traffic this build does not have, which is a precondition rather than a boundary.

## Where these came from

The rows were drawn from what the code says rather than from imagination, and where a closed item had
already stated a standing absence it is cited rather than restated:
[B-16](../backlog/B-16-traffic-simulator.md) (the simulated feed),
[B-19](../backlog/B-19-roaming.md) (no real network attachment),
[B-20](../backlog/B-20-custom-package-builder.md) (one tariff function, no campaign layer),
[B-26](../backlog/B-26-observability-wiring.md) (alerting, and the proxy header the stand believes),
[B-40](../backlog/B-40-no-way-to-add-money.md) (no card details),
[B-78](../backlog/B-78-one-line-one-esim.md) (a second profile meaning a second device).

Two of those are deliberately **left out** of the table above because they are one item's scope rather
than a standing boundary: `B-40`'s missing top-up in the operation history is a query change waiting
for a decision about ordering, and `B-42`'s uncovered Kotlin/Native test tasks are a gap in a guard,
not a product boundary.

## Anchors

| What | Where |
|---|---|
| The catalogue, in Kotlin | `feature/purchase-server-data/.../StaticPlanCatalog.kt`, `server/src/main/kotlin/io/konekt/tariff/TariffData.kt` |
| The mocked boundary ports | `feature/esim-server-domain/.../EsimPorts.kt`, `feature/purchase-server-domain/.../PaymentGateway.kt`, `feature/auth-server-domain/.../AuthPorts.kt` |
| Every table there is | `shared/db/src/main/resources/db/migration/` |
| One audience per deployment | `shared/server-common/src/main/kotlin/io/konekt/money/MoneyFormat.kt`, `shared/server-common/src/main/kotlin/io/konekt/money/DayFormat.kt` |
| The realtime bus, in memory | `server/src/main/kotlin/io/konekt/Application.kt` |
| What a rebrand *does* cost | [operator-boundaries](operator-boundaries.md) |
