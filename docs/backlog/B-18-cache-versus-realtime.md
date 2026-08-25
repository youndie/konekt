---
id: B-18
title: "Answer in writing how the screen cache and a live update interact"
status: done
priority: P2
size: S
stage: stage-m2-live
blocked_by: [B-15]
---

# B-18 — Answer in writing how the screen cache and a live update interact

This item is a question, and what closes it is a written decision with its reasoning. Read on
2026-08-25 out of `kompot-client-cache`, `kompot-realtime` and `kompot-client` at **`0.31.0.74`** —
the version this repository pins — plus konekt's own `SseRealtimeSource`, `RealtimeRouting`,
`HomeScreen` and `UsageConsumer`. The observation is recorded as a fact in
[research-architecture](../research/research-architecture.md) §1.15; open question 1 in §3 now points
here. The code that follows from the decision is **not** part of this item and is proposed separately.

## What the two halves actually do

Neither name survives contact with the bytecode, and the second one is where the value of this
exercise is.

**The cache is a one-shot read that cannot talk back.** `CachedKompotScreenProvider(store, fetcher,
json, scope)` has two public entry points. `suspend getScreen(key): KompotComponent` is the one that
reads:

| Read | What it does |
|---|---|
| cache hit | `scope.launch { revalidate(key, entry.etag) }`, then returns `decode(entry.payload)` — the stored screen, immediately |
| cache miss | `fetcher.fetch(key, null) as Modified`, `store.put(entry)`, returns the fetched component |
| `revalidate` | `fetch(key, etag)`; on `Modified` it writes `store.put(entry)`, on `NotModified` it does nothing. It returns `Unit` |

The other is `suspend invalidate(key)`, which is `store.clear(key)` and exists for the case where the
**client** knows a screen is stale without waiting — its own comment says a caller "that just
submitted a form which changes what this screen shows drops the entry explicitly", so the next
`getScreen` takes the blocking cache-miss path. It is not a substitute for item 2 of the decision
below: it answers "I caused this change", not "the server changed under me". It is the right tool
after a purchase, and that is recorded under what this decision does not cover.

`revalidate` has **no flow, no callback and no listener**. A revalidation that finds a changed screen
updates the store and reaches nobody: the caller of `getScreen` already has its component and is never
told. The refreshed screen becomes visible on the **next** `getScreen`, and nothing in the toolkit
issues one.

Two more facts from the same read, both of which change what may be claimed about freshness:

- `CachedScreenEntry` carries `fetchedAt`, and **nothing reads it**. `getScreen` touches `payload`
  and `etag` only. There is no TTL and no expiry: a cached screen is served whatever its age.
- The module's runtime dependencies are `kompot-core`, `kotlinx-serialization-json` and
  `kotlinx-coroutines-core` — **no ktor**. `KompotScreenFetcher` is an interface taking
  `(key, etag?)`. So "ETag revalidation" is a *contract shape*, not an implementation: the
  conditional request, the `If-None-Match` header and the 304 are konekt's code to write, and so is
  the `ETag` on the server, which today emits none anywhere (the only mention in the sources is the
  comment in `server/src/main/kotlin/io/konekt/theme/ThemeRoutes.kt` saying there is not one).

**A live update does not replace anything in the tree.** This is the finding.

`UpdateComponentMessage` is `(componentId, component)` and `KompotScreenResponse` is
`(screen, realtimeTopic)` — **no version, no sequence number, no timestamp on either**. On the client,
`KompotRealtimeProvider(topic, source, content, onUpdate)` is:

```
val updates = remember(topic) { mutableStateMapOf<String, KompotComponent>() }
LaunchedEffect(topic, onUpdate) {
    source.subscribe(topic).collect { updates[it.componentId] = it.component; onUpdate?.invoke() }
}
CompositionLocalProvider(LocalKompotRealtimeUpdates provides updates) { content() }
```

and `KompotRegistry.RenderNode(node)` begins by consuming that composition local:

```
val effective = LocalKompotRealtimeUpdates.current[node.id] ?: node
renderers[effective::class]        // the renderer is chosen from the REPLACEMENT's class
```

So an update is an **overlay map keyed by component id, consulted at render time**, not a mutation of
the screen. It sits *above* whatever tree is being drawn — cached or freshly fetched, this screen or
another one — and it wins, unconditionally, for as long as the composition lives.

**The map has exactly one eraser and it does not fire here.** `remember(topic)` drops the map when the
topic changes or the node leaves the tree — the toolkit's own comment says so, and says it is why no
manual `clear()` is needed. konekt has neither event: it serves one topic per subscriber, and
`SseRealtimeSource` reconnects **inside** one flow, so neither the key nor the `LaunchedEffect`
restarts across a stream gap. Nothing removes a single entry in any case; the granularity on offer is
the whole map, keyed by a value konekt never changes.

## The answer to the question as asked

The hypothesis on record was: *the cache stores the screen as fetched and updates apply on top in
memory, so a cold start shows the stale value for exactly one request.*

**Both halves of the first clause are confirmed.** The cache stores what the fetcher returned,
re-encoded; it never sees an `UpdateComponentMessage` — it has no reference to the realtime source and
no API to patch an entry. Updates do apply on top, in memory, in the composition.

**The last clause is refuted, and not on a technicality.** A cold start over a warm cache paints the
stored screen against an empty overlay, and it stays stale for as long as the screen is on display:
`getScreen` is one-shot, `revalidate` cannot deliver, and no second read happens until something
re-enters the screen. "Stale for one request" describes a client that re-asks. Nothing re-asks.

And the question in the item's title has a second answer that nobody was looking for. The dangerous
direction is the reverse one:

> **A pre-gap overlay entry shadows a post-gap fetch, permanently.** The stream drops; the server
> broadcasts into a topic nobody is collecting and the frame is discarded, which is by design
> (`Last-Event-ID` is deliberately unused — the client announces the gap instead). The client
> reconnects, `streamRestarted` fires, the screen refetches and receives the correct current counter.
> `RenderNode` draws the overlay entry from before the gap anyway, because the map still holds it and
> the map is preferred over the node. The screen is now permanently wrong for that component id, with
> a green network, a fresh fetch, a live stream, and no error anywhere.

This defect is **in neither of the two components the question was about**. It is in the seam, it is
independent of the cache, and it would have survived every way of "solving" the question by turning
the cache off.

## The decision

1. **The cache stays cache-first and stays on, unchanged.** The toolkit's behaviour — serve the
   stored screen, revalidate behind it — is the offline-first property the cache is here for.
2. **The screen holder re-asks once when a revalidation reports a change.** Since the toolkit cannot
   deliver that news, konekt's own `KompotScreenFetcher` implementation does: it is the code that
   sees 200 versus 304, so it announces "changed" on a flow of its own, and the holder calls
   `getScreen` again — by then the store holds the new payload. No fork (D9): the interface is
   designed to be implemented, and this is the implementation doing more of its own job.
3. **The overlay is cleared exactly once, on a stream restart, before the refetch.**
   `SseRealtimeSource.streamRestarted` (B-15) exists and has had no consumer; this is it. konekt
   therefore collects the source itself and provides its own map into `LocalKompotRealtimeUpdates`
   rather than using `KompotRealtimeProvider`. The local workaround carries a comment naming the
   upstream issue, per the repository's rule.

   **The cheaper-looking route was weighed and costs more.** `remember(topic)` is a real eraser, so
   wrapping the provider in `key(restartCount) { … }` would empty the map through the toolkit's public
   API with no reimplementation. It also re-runs `LaunchedEffect` and therefore `source.subscribe` —
   and `streamRestarted` fires *after* `SseRealtimeSource` has already reconnected, so this tears down
   a healthy subscription to clear a map, once per gap, and re-opens the window it was closing.
   Mangling the topic string instead is worse still: the topic is the server's address, not a local
   cache key. What the toolkit is missing is not an eraser but an eraser whose granularity is smaller
   than "resubscribe", and that is the shape of the upstream proposal.
4. **konekt does not arbitrate an update against a fetch by content, because nothing on the wire
   permits it** — neither the frame nor the screen response carries a version. One owner applies both
   in arrival order, and that is the whole ordering rule. What makes it acceptable rather than
   resigned is measurable in this repository's own code: the frame and the screen are built by **the
   same function**. `UsageConsumer` pushes `cards.of(updated)` and `HomeScreen.build` renders
   `counters.map(cards::of)`, both through `UsageCounterCards`, and the id is `idOf(counter)` in both
   places. A mis-ordered apply therefore shows a value that was true a moment ago and is corrected by
   the next event — bounded by one update interval. The unbounded failure is the stale overlay, and
   (3) is what ends it.

## The alternatives, and why each is worse

**Disable the cache for screens that have an update channel.** Rejected, and the reading strengthens
the rejection rather than merely restating it. It buys freshness by giving up offline-first, which is
the one reason `kompot-client-cache` is in this build at all — and, decisively, it does not touch the
defect that actually exists. The stale overlay is a property of the realtime half alone; a client with
no cache at all reproduces it exactly. An alternative that makes the question unaskable while leaving
the bug in place is the worst of the three, because it also removes the occasion to find it.

**Write updates through into the cached screen, so a cold start replays them.** Rejected on the
mechanism: an `UpdateComponentMessage` names a component id and no screen, so nothing in the frame
says which cache entry to patch; the store's API is `get`/`put`/`clear` on a whole entry, so patching
one means decoding, walking, re-encoding and re-storing the entire tree on every frame. And it is
wrong in the product sense too — persisting an update whose truth expires means a cold start can
confidently paint a number that was superseded while the application was closed, which is worse than
painting the last screen the server actually built.

**Version the wire and arbitrate.** A sequence number on `UpdateComponentMessage` would make the
ordering decidable instead of assumed. Rejected *here*: it is a change to a shared contract in a
toolkit, for a race whose damage is bounded to one interval by the shared builder above, and a client
that trusts a sequence must also handle the sequence resetting when the server rolls. It is a
reasonable upstream proposal and an unreasonable local patch (D9).

## What this decision deliberately does not cover

- **Age.** With `fetchedAt` never read, the first frame after a cold start is a screen of unknown age.
  The decision adds no TTL and no "as of" marker; the corrective it does add is the re-ask, which
  narrows the window rather than labelling it. A screen that must never be shown stale is a screen
  that must not be cache-first, and there is no such screen today.
- **Offline.** With no network the cache serves and the stream never connects. The subscriber is shown
  old numbers with nothing saying so, and the component dictionary has no vocabulary for saying so.
- **A tree of mixed freshness.** On the home screen only the counters have an update channel;
  `balance-amount` changes on a fetch alone. After a purchase the two are briefly inconsistent, and
  nothing here makes them agree.
- **Two updates that must agree.** The overlay is per component id and each frame is an independent
  write, so a pair that should land together can be seen torn. konekt's two counters are independent
  today, which is why this costs nothing yet.
- **Id collisions across screens.** The overlay map is keyed by component id and **not** by screen, so
  two screens using one id shadow each other. konekt's ids (`counter-<kind>`, `balance-amount`,
  `home`) happen not to collide; nothing checks that they do not.
- **Refetching after konekt's own mutation.** `invalidate(key)` is the toolkit's answer to "I just
  changed this myself", and a purchase changes the home screen. The decision does not wire it: item 2
  narrows the server-changed-under-me window, and the purchase flow lands on a result screen rather
  than returning to a cached home, so nothing is observably stale today. It becomes wrong the moment
  the purchase returns in place.
- **Every screen that is not the home screen.** The history page is paginated through `LoadPageAction`
  and is not a cached screen; the wizard is a step machine. The subject here is the counter screen,
  as the item asked.

## Acceptance

- AC — *research §1 carries either the confirmation or the refutation, with what was observed*:
  **met**, [research-architecture](../research/research-architecture.md) §1.15, with the split between
  the confirmed clause and the refuted one and with where each was read.
- AC — *if the stale-for-one-request behaviour is confirmed, the counter screen states its own
  freshness*: **its condition does not hold.** The behaviour is not stale-for-one-request but
  stale-until-the-screen-is-re-entered, so the freshness label this AC anticipated is not the
  corrective; item 2 of the decision is. Stating an age is still open and is listed above as not
  covered.
- Anchors: `client/src/commonMain/kotlin/io/konekt/client/realtime/SseRealtimeSource.kt`,
  `server/src/main/kotlin/io/konekt/realtime/RealtimeRouting.kt`,
  `server/src/main/kotlin/io/konekt/screens/HomeScreen.kt`,
  `server/src/main/kotlin/io/konekt/mocks/traffic/UsageConsumer.kt`,
  `gradle/libs.versions.toml` (`kompot-clientCache`, catalogued and depended on by nothing).

Background: [research-architecture](../research/research-architecture.md) §1.15 and open question 1;
the live channel as it stands is [endpoint-home](../api/endpoint-home.md), the screen is
[screen-home](../screens/screen-home.md).
