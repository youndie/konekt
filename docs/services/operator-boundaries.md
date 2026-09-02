---
id: operator-boundaries
title: What an operator can change, and what it costs
type: service
status: active
repo_url: https://github.com/youndie/konekt
# EVERY MODULE, and that is what this document is: the axes cut across all three, and which cost an
# axis carries is decided by which of them has to change.
module: server, client, broker
tech_stack: [Kotlin/JVM 25, Compose Multiplatform, booblik, kompot]
owner: unassigned
tags: [white-label, operations, boundaries]
---

# What an operator can change, and what it costs

The claim this build demonstrates is that a brand ships from the server and the client applies it
without a rebuild. That is true, with boundaries — and **a claim whose boundaries are discovered by
the reader is worth less than a narrower claim with its edges drawn.** So they are written down here,
per axis, with the research section that establishes each one.

Written as a table because a paragraph lets the awkward rows hide. What is not on any axis at all —
the things this build deliberately does not do — is [reference-scope](reference-scope.md).

## The five costs

| Cost | What it means |
|---|---|
| **configuration** | an environment variable and a restart of one process |
| **server deploy** | a new server image; clients are untouched |
| **client release** | a new build in the stores, on the subscriber's update schedule |
| **broker restart** | booblik's topics are fixed at startup, so the broker itself must be restarted |
| **not available** | the wire has no vocabulary for it, so there is no price. Not slow — impossible without a change to a toolkit |

The order matters: the first four get slower going down, and the fourth is on a subscriber's schedule
rather than an operator's. The fifth is not on that scale at all, and it is here because **a price
list that silently omits what is not for sale is read as a complete price list.** One axis carries it
today, and one axis is enough to need the column.

## The table

| Axis | Cost | Why | Established in |
|---|---|---|---|
| Colours | server deploy | The kit is served over HTTP and the client applies it without a rebuild — but the kits themselves are resources inside the server image (`server/src/main/resources/themes/`), so a NEW palette is a deploy. This is the axis the rebrand is demonstrated on. | [§1.2](../research/research-architecture.md), [§1.3](../research/research-architecture.md) |
| The type **scale** — sizes, weights, letter spacing | server deploy, **never yet done** | `KompotTheme` carries a `typography` block, so a scale could travel exactly as a palette does. Neither kit in this build contains one, so nothing here has ever exercised it. | `server/src/main/resources/themes/` |
| The **font family** | **not available** | `KompotTextStyle` carries size, line height, weight, letter spacing and colour, and no family. A face named by a server would not arrive. | [§1.2](../research/research-architecture.md), [design-brand-kit](../design/design-brand-kit.md) |
| Which of the shipped brands is served | **configuration** | `BRAND` picks among the kits the image already carries. This is the only row that is a variable and a restart. | `KonektConfig.brand` |
| Copy, screens, layouts, flows | server deploy | Every string and every tree is composed on the server; the client renders what it is given and formats nothing (D15). | [§1.2](../research/research-architecture.md) |
| A new value in an open vocabulary — a counter state, an order status, a plan state | server deploy | These are open strings on the wire on purpose. A client one release behind draws the ordinary card rather than nothing. | [§1.5](../research/research-architecture.md) |
| **An icon** — a new one, or a different drawing of an existing one | server deploy | The odd one out on this list, and deliberately. An icon carries no behaviour: no renderer, no action, no layout. Pricing it like a component would have meant a client release for a picture, so `B-110` put the SHAPE on the wire — SVG path data, drawn by `PathParser` on a `Canvas`, the same arrangement the eSIM QR already used. The client still decides the COLOUR, from the role the control asks for, which is why a rebrand can repaint icons it has never seen. What a client one release behind cannot do is nothing: `BottomNavItem.icon` is nullable, so an older build draws the label alone. | `VectorIcon`, `VectorIconGlyph` |
| The shape scale (corner radii) | **client release** | The wire has no vocabulary for shape and kompot protects that deliberately. The client resolves a brand NAME to a scale it was compiled with. | [§1.2](../research/research-architecture.md) |
| A brand the client has never heard of | **client release** to get its shapes | It is served and rendered immediately — with brand A's radii, silently. `BrandKitsTest` fails when the server ships a kit no scale answers for, so the gap is caught in CI rather than by a subscriber. | [§1.2](../research/research-architecture.md) |
| A new kind of component | **client release** | The dictionary is the API. An unknown type draws the degradation block — never a blank gap — and is reported with its wire name, so an operator can see which build is behind and how often. | [§1.5](../research/research-architecture.md), [§1.4](../research/research-architecture.md) |
| A new kind of **action** — a verb a control sends | **client release** | Separate from the row above and the same price. A component is generated into the registry; an action is registered by hand in a `SerializersModule` on each side, so a client that has not been rebuilt decodes it as `UnknownAction` and its handler chain has no branch for it. **Measured**: `B-86`'s two screens drew on an unrebuilt Android build straight from the server, and pressing a tariff logged `no handler for UnknownAction(originalType=change_tariff)` and moved nothing. That action no longer exists — `B-102` removed it with the screens — and the measurement stands: it is evidence about the mechanism, not about the tariff. | `konektActionWireNames`, [§1.13](../research/research-architecture.md) |
| A new event topic | **broker restart** | booblik fixes its topics at startup and has no replication. | [§1.8](../research/research-architecture.md) |
| The language | server deploy, **and one at a time** | There is no `stringResource`, no `Accept-Language` and no bundle anywhere: every string is an English literal in the server's Kotlin — `HomeScreen.kt`, `PlansScreen.kt`, `RoamingZoneNames.kt`. So a second language is a second deployment, not a second header. | [reference-scope](reference-scope.md) |
| The currency | server deploy | `MoneyFormat` carries its own layout table for five currencies and states the assumption: *the product has one audience per deployment*. | `shared/server-common/.../MoneyFormat.kt` |
| The date format | server deploy | `DayFormat` pins `"d MMM"` and `Locale.ENGLISH`. The client formats nothing, so this is the only place it can change. | `shared/server-common/.../DayFormat.kt` |
| The time zone | server deploy | `DayFormat` pins `ZoneId.of("UTC")`, and `B-33` records the billing boundary being computed in one fixed zone. Everything stored is an instant, so this is a formatting decision and not a data migration. | `B-33` |
| The application's name and icon | **client release** | `androidApp/src/main/AndroidManifest.xml` carries `android:label`, and `scripts/ios-home-app.sh` writes `CFBundleName` by hand for a simulator bundle. Two places, both inside a client build — which is what makes the cost a release rather than a deploy. | `androidApp/src/main/AndroidManifest.xml`, `scripts/ios-home-app.sh` |
| Icons in the interface | server deploy | Since `B-114` the dictionary has an `icon` — a `VectorIcon` in a disc, coloured by tone — and every glyph the client draws (tab icons, the back chevron, the outcome marks) is path data on the wire, drawn by `VectorIconGlyph`. The client decides the colour from the tone and the size from the component; a client one release behind renders an unknown component as nothing, which for a mark beside a headline is a missing picture, not a broken screen. | `IconComponent`, `IconRenderer`, `VectorIconGlyph` |
| What a card is — a card, a chip, a table, a footer | server deploy | `surface` carries `tone` (`neutral`/`accent`/`alert`), `density` (`card`/`chip`), `dividers` and `pinned` (`B-114`). The corner radius, the inset, the hairline colour and where a pinned footer sits are the client's; the server only says which of the four this is. (The corner alone has been kompot's since `0.34` — `background.role` — so a plain column could round today; the type stays for the other three words.) An older client ignores the three fields and draws every surface as a card — a chip becomes a wide card and a footer scrolls with the page, which is the pre-`B-114` picture, not a broken one. | `SurfaceComponent`, `SurfaceRenderer`, `KonektShell.withoutShell` |
| How much a button matters — `primary`, `quiet`, `tonal`, `link`, `danger` | **client release** for a new word, server deploy to use one | `button.variant` is an open string kompot leaves to the design system, and `KonektDesignSystem` is where konekt names the set: `quiet` is the outlined pill, `tonal` (`B-114`) the tinted one under the counters, `link` the text under a pill and `danger` the same in the error colour — the one row that leaves. A client without the word draws its ordinary button — wrong and harmless, which is why the fallback is that way round. Choosing between the words a client already knows is the server's. How tall a control is — 56 for a pill and a field, 44 for a text row — is the design system's too, since kompot `0.35.0.103` (#106), and moves in a client release. | `ButtonEmphasis`, `KonektDesignSystem` |
| What a plan card offers — the `Choose` pill and the tag | server deploy | `plan_card.actionText` (`B-114`) is the pill's word, pressing the card's own `action`; `badgeText` is the tag, and since `B-114` it is sent only when there is something to say — `Sold out` — rather than `On sale` under every card. The chip and the pill are drawn by the same renderers every other chip and button use, composed through the registry, so their look is the client's and moves with it. A client that predates the field draws the card without the pill, and the card is still the press target it always was. | `PlanCardComponent`, `PlanCardRenderer`, `PlansScreen.card` |
| A screen's own back control and title | server deploy | `screen_header` (`B-115`): the title and the one control on the left — a chevron that presses the action it carries (a wizard's step back), or a cross that leaves. The shell pulls it out of the tree the way it pulls the bar and the pinned footer and draws it in its chevron's place; a client that predates it draws the row in place through the registry, at the top of the content rather than above it. What the circle looks like is the client's; what it does is the server's. | `ScreenHeaderComponent`, `ScreenHeaderRenderer`, `KonektShell.withoutShell` |
| Putting text on the clipboard | server deploy | `copy` (`B-115`) is an action carrying the text; the client answers it on its own clipboard and it goes no further — not to the host, not to the server. That is the contract: what is copied here is an activation code, and a copy that reported back would put a credential in an access log. A client that predates the action draws the button and presses nothing. | `CopyAction`, `KonektApp` |
| The plan catalogue and its prices | server deploy | `StaticPlanCatalog` is in the server's code. A real MVNO reads a BSS; this build does not, and says so. | `feature/purchase-server-data` |
| The tariff behind a custom package | server deploy | One function, no campaign layer. The client is never given a price table — a price computed on the client is a price a client can argue with. | [feature-plan-purchase](../features/feature-plan-purchase.md) |
| A database schema change | server deploy, **twice** | Expand and contract are separate releases. A differ emits the shortest SQL that makes two schemas equal, which is `DROP COLUMN` and `RENAME` — exactly what breaks a rolling deploy. | `B-36` |
| Where the observability agents report | **configuration** | Endpoint and key per agent. Both absent is a decision; one absent is refused at startup, because a deployment that meant to be observed and is silent looks exactly like one that is working. | [§1.9](../research/research-architecture.md) |
| Crash reporting on iOS | delivered | katcher publishes every Apple target since `client:0.6.2`, and a simulator crash arrives naming its release. | [§1.9](../research/research-architecture.md), `B-27` |
| Structured logging on iOS | delivered | tracy publishes the three iOS targets since `0.1.13`. Before that it was unavailable on the platform where an out-of-date build is likeliest. | [§1.9](../research/research-architecture.md), `B-26` |

## The rows worth arguing with

**"Colours are a server response" is true and is not configuration.** A client applies a served kit
without a rebuild, which is the claim that matters for the update schedule — and the kit is a file in
the server image, so producing a new one is a deploy. Both halves are true and only one of them is
usually said.

**A brand without a shape scale is served successfully and looks wrong.** The client falls back to
brand A's radii silently, because refusing to draw a screen over a corner radius is worse than drawing
it with the wrong one. What makes that safe is not the fallback: it is the test that fails when the
server ships a kit no scale answers for.

**Two rows have no price, and that is the point of listing them.** Interface icons and the font family
are the axes where the answer is not "how fast" but "not through this wire". A table of rows that each
name a cost implies that everything has one, which is exactly the impression a reader carries away
from a table with no such column. Neither is a defect of konekt: both gaps are in the toolkit, and
closing either is an upstream proposal rather than a deploy.

**"Typography" was one row and is three.** A reader pricing a rebrand reads the word and thinks *the
operator's face* — which is the one of the three that cannot be bought, and it used to sit in the same
cell as the one that can.

**The slowest row of the four is the one an operator does not control.** A client release lands on the
subscriber's schedule, not the operator's — which is why the two rows that need one are the two the
architecture works hardest to avoid needing.

## Not covered

Pricing, packaging and anything commercial. Also anything about a real BSS, OCS, SM-DP+ or payment
provider: every external system in this build is a mock, and what it would cost to change a real one
is a fact about that vendor rather than about this build. Which of them are mocked on purpose, and
what would end each mock, is [reference-scope](reference-scope.md).
