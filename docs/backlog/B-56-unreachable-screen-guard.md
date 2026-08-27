---
id: B-56
title: "Nothing fails when a screen the server serves is the destination of no action anywhere"
status: done
priority: P1
size: S
stage: stage-m4-proof
epic: feature-client-shell
---

# B-56 — Three features shipped unreachable, and every existing guard was green for all three

The confirmation, the top-up and the eSIM wizard each shipped with a working server half and nothing
that led to it. The guards this repository already has all passed, correctly, because each answers a
different question:

| Guard | What it proves | Why it was silent |
|---|---|---|
| `FeatureModulesReachTheGraphTest` | the module is in the composition root | it was |
| `RoutesResolveWhatTheyInjectTest` | the routes' injections are bound | they were |
| `KonektRegistrationTest` | every component decodes | they did |
| `TckCoverageTest` | the walk visits every endpoint it can | it did, blind GETs and all |

What none of them asks is whether any tree the server emits contains an action that leads to a given
screen. That is a property of the DATA the server sends, not of the object graph — which is why it
needs its own check rather than an extra assertion on an existing one.

- **The decision and its reason.** The check reads the SERVED trees, not the source: a `grep` for a
  deeplink constant would pass on a constant used in dead code, and the failure being caught is
  precisely "written and never emitted" ([[written-but-never-called]] is the shape). The e2e stand
  already walks every screen — collecting the actions it saw and comparing them against the set of
  screen endpoints is a few lines on top of a walk that happens anyway.
- **The exemption list is the dangerous part.** A screen legitimately reachable only by deeplink,
  or only from a client control, has to be declared — and a declaration is how this guard becomes
  vacuous. So it is declared per screen with a reason, the way `KONEKT_UNWALKED_ENDPOINTS` is, and
  the list is asserted to be EXACTLY the set that is unreachable rather than a floor.
- **Not covered:** whether the destination is reachable in a sensible number of presses. That is
  design, not a gate.
- AC: deleting the `Top up` button makes this check name the top-up screen, and nothing else.
- AC: the exemptions are a set equality, so a screen that becomes reachable and stays on the list
  fails.
- Anchors: `e2e/src/test/kotlin/io/konekt/e2e/`,
  `server/src/testFixtures/kotlin/io/konekt/conformance/KonektConformance.kt`.

Background: [B-54](B-54-the-esim-wizard-is-unreachable.md) is the third instance and names the shape.

## What landed

`EveryScreenIsReachableTest`, in `:client:standTest` — so it reads the client's OWN route table
(`KonektRoutes`) against a running deployment, and a deeplink the server sends that the client cannot
resolve fails it too. The walk fetches every address the client knows plus the purchase result (which
the runner reaches by answering an action rather than by a deeplink), collects every `navigate` in
the bodies, and asserts the set nothing points at is EXACTLY the two declared ones: the login screen,
where the application opens, and the code step, reached by an endpoint's answer.

**It bit on the first run, twice, and both were about the guard.** It reported the home and profile
screens as reachable from nowhere: the first version walked the decoded tree by reflection, following
members that were components or lists of them — and `BottomNavComponent` holds `items` of
`BottomNavItem`, which is neither a component nor an action but a holder CARRYING one. All four tabs
were stepped over, and the comment above the walk claimed they were not. It also reported
`esim-install`, because the screen that leads there is the purchase result and the walk was only
fetching deeplink destinations.

Reading the JSON instead removed both blind spots and the list of shapes to keep in step with the
dictionary: an action is an object whose `type` is `navigate` wherever it sits. The wire name is read
off `NavigateAction.serializer().descriptor`, not typed.

**Proved to bite.** Deleting the `Install eSIM` button and rebuilding the stand makes it name
`/api/v1/screens/esim-install` and nothing else; restoring it goes green. That is the check
[B-54](B-54-the-esim-wizard-is-unreachable.md) needed and did not have.

**One thing it forced open first.** The route table was written inline in each runner, so there was
nothing a guard could be handed — and the two copies had drifted: the desktop knew six deeplinks and
the iOS runner three. Hoisting it into `KonektRoutes` fixed that drift, and reading the iOS runner to
do it found the next one: it imported `UpdateSessionAction` and `SessionTokens`, held a session and
handled neither, so **the iOS build could not get past its login screen**. Every part existed except
the branch.

**Not covered:** screens the server serves that the client has no address for at all. The subject
here is the client's table, so a screen nobody wired anywhere is invisible to this — it would show up
in `TckCoverageTest`'s walked set instead, as an endpoint reachable only by the kit.
