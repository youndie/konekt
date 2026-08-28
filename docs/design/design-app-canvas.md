---
id: design-app-canvas
title: konekt — the application design canvas
type: design
status: active
date: 2026-08-25
---

# The design canvas, and what it commits konekt to

Source: Claude Design project *Mobile app for konekt*,
`https://claude.ai/design/p/d76fd006-5a1d-46ca-80f4-c3fd151f76c7`, file `Konekt eSIM app.dc.html`.
A copy of the canvas markup is kept beside this document as
[`konekt-esim-app.dc.html`](konekt-esim-app.dc.html); it needs the project's `support.js` to render,
so the canvas link above is the live one and the copy is the record.

Nine sections, 393×852, light and dark side by side, every state as its own frame:

| # | Section | What it fixes |
|---|---|---|
| 01 | Home & balance | the counter card, its normal / low / exhausted states, the balance block |
| 02 | Plans catalog & plan detail | four list states in one frame: available, tonal alternate, sold out, loading skeleton |
| 03 | Purchase — confirm, processing, success, rollback | the four states of a saga, including the compensated one |
| 04 | eSIM install — frame by frame | four steps plus the QR frame in dark, and the slot-limit failure |
| 05 | Orders & profile | the order row, the refund line, the support block |
| 06 | Component dictionary — all states | every control in every state, including the unknown-component block |
| 07 | App icon — sketches | vector sketches, explicitly not finished artwork |
| 08 | Brand B parity | the same markup on an ink palette with tighter radii |
| 09 | Login & SMS code | the two steps as the server already builds them, plus one richer frame the canvas prices rather than specifies |

**Section 09 was added after `B-46` shipped the screen, and it is drawn FROM the code.** Five of its
nine frames are `LoginScreens.kt` rendered out with the server's own strings, which makes them a
record rather than a request — they were checked against the source and match. The last frame is
labelled *"needs client work — do not read as a spec"* and prices four additions instead of
demanding them. Two of those prices are too high, because `TextInputComponent` already carries
`placeholder` and `mask`; see [B-50](../backlog/B-50-login-frame-six.md), which is where the
corrections live.

## What the canvas already knows about the stack

The designer worked against the toolkit rather than around it, and three of the notes on the canvas
are findings in their own right. They are verified in
[research-architecture](../research/research-architecture.md) §1.2 and §1.5:

- colour swatches carry the wire token names of `kompot-ds-material` — `primary`, `primary_container`,
  `secondary`, `surface_variant` and the rest of the Material 3 roles;
- the form frames use the real field types of `kompot-forms` — `text_input`, `amount_input`,
  `autocomplete_input`, `checkbox_input`, `radio_group`, `select_input`, `read_only_field` — and every
  one of those exists on the wire;
- *"radii are a client build constant — the server theme carries colours and typography only, so brand
  B's shape change needs a client release."* Verified, and it is a deliberate property of kompot
  rather than a gap. It is the reason for decision D2.

Section 08 exists to prove the layout survives the shape swap: brand B changes `lg` 36→22, `md` 20→12
and turns pills into rounded rectangles, and nothing in the layout depends on it. That is what makes
a client-side shape constant cheap rather than dangerous.

## The component dictionary konekt has to own

Everything below is drawn in section 06 and has no wire type in the toolkit. These are konekt's own
components, in one KSP module with its own `kompotModuleTag`. **Built and fixed in `B-03`** — the
names below are what is on the wire, and `shared/spec/schema/konekt-components.schema.json` is
generated from the types themselves.

| Canvas element | Proposed wire type | Why it is not a toolkit component |
|---|---|---|
| counter card with progress | `usage_counter_card` | a quota, a remainder and a projection in one control; the low and exhausted states change the copy, not only the colour |
| plan card | `plan_card` | price, quota triple, zone and availability state, including sold out and skeleton |
| QR block | `esim_qr` | renders an activation code as a QR locally; the code never becomes an image on the server |
| eSIM card | `esim_card` | ICCID in the mono face, lifecycle status, the actions each status permits |
| order row | `order_row` | reference, date, amount, and a refund line that reads in money |
| banner | `banner` | info / low / error, inline in the flow |
| snackbar | `snackbar` | transient, outside the tree |
| step meter | `step_meter` | "step 3 of 4"; the wizard's own progress, not a generic progress bar |
| skeleton | `skeleton` | the loading state of a list row, drawn rather than absent |
| unknown block | replaces `UnknownComponent` | not a new wire type — a replacement renderer, see research §1.4 |

Nine, not ten. The tenth was a switch, and it is a toolkit component after all:
[kompot#82](https://github.com/youndie/kompot/issues/82) closed on 2026-08-25 and
`CheckboxInputComponent` now carries `variant`, with `KompotCheckboxVariants.SWITCH` the word the
standard renderer acts on. A settings toggle is `checkbox_input` with that variant, and no component
of ours.

The nine carry **pre-formatted text**, not numbers with units — `valueText` is `"15,8 GB left"` and
`priceText` is `"1 190 ₽"`. That is the backend-driven bargain taken deliberately: the server builds
the screen, so the server formats, and a client that cannot format money cannot format it
inconsistently. The exceptions are geometry rather than language — a counter's `progress` fraction and
a step meter's two integers — because a bar and a "3 of 4" cannot be drawn from a sentence.

## What the canvas asserts that the build must be able to reach

Three frames describe states that a naive implementation can never enter, and each is a backlog item
rather than a picture:

**The rollback frame.** *"The provider declined the operation. We reversed the hold — your balance is
back to where it was, and no eSIM was issued."* Stated in money, with the reference to quote to
support. This is the compensated branch of the purchase saga, and it is the reason the payment mock
takes a refuse switch (research D10).

**The slot-limit failure on install step 1.** Eight-profile limit reached — named on the canvas as
*"the failure this flow actually hits in the field"*. The SM-DP+ mock therefore has to be able to
refuse for that reason specifically, not merely to fail.

The canvas names the failure and does not supply the sentence, so **the copy in that frame is ours**,
written in `MockSmDpPlus` and asserted in `MockSmDpPlusTest`: it states the limit as a fact about the
device and says what to do next, because "could not add eSIM" is what sends somebody to support. It
is recorded here rather than left implicit for the same reason as the currency below — a reviewer
comparing the frame with the running application should know which of the two is the source.

**The unknown-component block.** *"The server sent a component this build does not know. Everything
around it still works — update to see it."* Two densities: a full card when the block is the screen's
subject, one line when it sits among known rows. The canvas labels the example
`type: esim_transfer_widget`. A client that registers everything the server sends can never draw this
frame, which is Risk 5 in the research and its own backlog item.

## The canvas is drawn in roubles and the product runs in dollars

Every amount on the canvas is a rouble — `1 190 ₽`, `2 480,50 ₽`. The product's currency is
`Currency.DEFAULT`, which is **USD**, so the same screens render `$1,190` and `$1,190.50`.

Recorded rather than quietly reconciled, because the difference is not only the symbol. A dollar is
written with the symbol in front, groups separated by commas and a fraction after a point; a rouble
is the mirror of all three. So a frame photographed from the canvas and a frame from the running
application differ in the shape of every amount, and a reviewer comparing them should expect that
rather than file it.

What the canvas still decides, and what `MoneyFormatTest` asserts: a whole amount drops its zero
fraction, and a history row carries an explicit sign while a balance does not. Those are product
rules and they survive the change of currency. Re-drawing the canvas in dollars is worth doing before
anyone uses it as an acceptance reference for a screen; it is not on the critical path of any item.

## Typography and shape

Manrope for the interface, Space Grotesk for figures, ICCIDs and activation codes. Both are Google
Fonts and both must be bundled in the client rather than fetched — `KompotTextStyle` carries size,
weight, line height, letter spacing and colour, and no font family, so a face named by the server
would not arrive anyway.

Spacing 4 · 8 · 12 · 16 · 20 · 24, minimum touch target 48. Shape scale per brand: A is `lg` 36 /
`md` 20 / `sm` 12 with pills; B is 22 / 12 / 8 with rounded rectangles.

Both scales are built, and what an operator can and cannot change without a client release is
[design-brand-kit](design-brand-kit.md). It also carries a measured caveat this section cannot: on a
button at Material's default 40dp height every radius of 20dp or more draws the same pill, so brand
B's 22 is indistinguishable from brand A's pill until the button is taller than 44dp.

## Which of these frames are photographed, and which cannot be

`B-28` put eight of the canvas's frames under a screenshot harness (viddik, a Gradle plugin). The
goldens live in `client/src/jvmTest/snapshots/`, `./gradlew :client:viddikVerify` compares them, and
`:client:check` runs that comparison.

| Frame | Golden |
|---|---|
| counter card, normal / low / exhausted (section 01) | `Counter_Normal.png`, `Counter_Low.png`, `Counter_Exhausted.png` |
| counter card, a state word this build does not know | `Counter_Unknown_state.png` |
| brand A and brand B, light and dark (section 08) | `Brand_A.png`, `Brand_A_Dark.png`, `Brand_B.png`, `Brand_B_Dark.png` |

The fourth counter frame is not on the canvas and is the most valuable of the four. `state` is an open
string on the wire (`CounterStates` in `shared/components`), so a server one release ahead can name a
state this build has never heard of, and the rule is that such a word draws the ORDINARY card. That
frame therefore carries THE SAME DATA as the normal one and its golden is asserted to be
pixel-identical to it — a degradation that draws something of its own fails, and so does one that
draws nothing.

**Every type this build serves has a renderer**, and every screen is photographed. The list is
`usage_counter_card`, `plan_card`, `esim_card`, `esim_qr`, `order_row`, `banner`, `snackbar`,
`step_meter`, `skeleton`, `bottom_nav`, `surface` — kept in step with the code by
`RendererCoverageIsDocumentedTest`, which fails if this sentence and `konektRenderers` disagree.

**The paragraph this replaces said the opposite, and had for three releases.** It read: *"this client
registers a renderer for two of the nine types only (`usage_counter_card` and `esim_qr`)"*, and gave
that as the reason sections 02 and 03 could not be photographed. It was true when it was written and
stopped being true when `B-45` shipped six renderers; the goldens for both sections arrived without
anybody touching the prose beside them. The claim was load-bearing — it is the stated reason two
sections had no frames — so somebody planning work against it would have priced a renderer that
already existed.

Prose next to generated artefacts is checked by nothing, which is why this one is checked now.

Recording is `LOCAL=1 ./gradlew :client:viddikRecord`, on the Mac: the Linux box is a one-way replica
and reverts anything a task writes there, so a recording run in the usual place looks like it did
nothing. The goldens then verify unchanged on Linux — measured, not assumed, and it works because the
fixtures pin viddik's bundled font rather than the host's.

## What section 01 draws and this build does not serve

Recorded here rather than left as a permanent difference, because a difference is only a defect once
somebody has decided which of the two moves ([B-51](../backlog/B-51-the-screens-against-the-canvas.md)).

| Drawn | Why it is not served |
|---|---|
| the avatar chip with initials | `subscriber` holds an msisdn and nothing else, so initials would be invented. It joins the day sign-up asks for a name — [B-55](../backlog/B-55-home-header.md). |
| `Smart 20 · renews 12 Sep` over the counters | there is no subscription. A purchase grants an allowance and that is the end of it, so nothing renews and there is no plan for the three counters to belong to. The container to draw the group exists — [B-60](../backlog/B-60-counter-copy-and-grouping.md). |
| a `Roaming` button beside `Buy a package` | the catalogue is one list and roaming plans are in it. A second button filtering the same screen is [B-57](../backlog/B-57-plans-catalogue-against-section-02.md)'s filters, not a control of its own. |
| the roaming package as its own row | it is drawn as a `usage_counter_card`, which carries the quota the canvas's row does not. What the row is FOR — the install control — is served as a banner on the same screen and on the same condition, and on the count of profiles held rather than on there being a roaming package: what makes an eSIM installable is holding none, and a home bundle needs one exactly as much as a trip does. |

**The counter values state what is LEFT**, and section 05's three-tab bar is not the four this build
serves — both already recorded below and in the tab note.

## Two decisions the canvas and this build settled differently

**A counter states what is LEFT, not what was used.** The canvas writes minutes and SMS as
`18 of 300` and data as `15,8 GB left`; this build says `left` for all three. The two read in opposite
directions and a subscriber who misreads the direction misreads their remaining balance, so the value
of picking one is higher than the value of matching each frame. `left` is the one that answers the
question somebody opens the screen with.

**The home header carries the operator's name and no avatar.** The brand kit gained a `displayName`
— it is a fact about the deployment, it lives in the file an operator already edits, and the server
builds the screen, so it needed no wire type. What is NOT drawn is the canvas's avatar chip: a
`subscriber` holds an msisdn and nothing else, so initials would have to be invented, and a circle
with made-up letters in it is a mockup wearing the product's clothes. It joins the day sign-up asks
for a name.

## What the canvas does not provide

The app icon is vector sketches, and the canvas says so: not finished artwork. A real icon set is an
illustrator's job or a supplied brand mark. It is a backlog item, not an oversight, and it is not on
the critical path of anything.
