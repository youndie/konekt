---
id: B-50
title: "The login screen the canvas draws last is four additions, and two of them are not what they look like"
status: open
priority: P2
size: M
stage: stage-m3-product
epic: feature-client-shell
---

# B-50 — The richer sign-in, and what each part of it actually costs

Section 09 of the canvas is nine frames. Five of them are the screen this build already serves —
drawn from `LoginScreens.kt`, with the server's own strings — and the designer says so on the frame.
Those were checked against the source line by line and they match: the column and its spacing, the
`RequiredRule` copy, the bound `read_only_field` carrying the number to step two, and the refusal as
a **banner** rather than a field error, verbatim from `refusalText`.

The last frame is a richer treatment, and it is labelled *"needs client work — do not read as a
spec"*. It is priced on the canvas, honestly, from what a designer could see. Two of the four prices
are wrong in the cheap direction, and the reason is worth more than the item: `TextInputComponent`
already carries `placeholder`, `mask`, `uppercase`, `secret` and `multiline`, and the toolkit's
desktop renderer reads all of them — verified in `kompot-forms-client-desktop:0.33.1.91`, not
inferred from the type.

| The canvas prices | What it actually costs |
|---|---|
| `code_input` — new type: KSP module, schema, renderer, both ends | **Right**, for the segmented six-box control it draws. A single field that merely *formats* six digits is `mask` plus `uppercase`, which are free. The choice is between the control and the formatting, and only the first is a wire type. |
| `prefix slot` — a new field on `TextInputComponent` plus a renderer change, precedent `amount_input.currencySuffix` | **Not needed for the frame as drawn.** The grey `+7 999 120-45-67` in an empty field is a PLACEHOLDER, which exists and renders. A permanently visible, non-editable `+7` is still a new field — but nothing in the drawing asks for one. |
| `countdown` — no clock in the payload; cheapest honest version is a bound `read_only_field` the server refreshes | **Right.** `RequestOtpResponse` carries `resendAfterSeconds`, and it goes to whoever called the DTO endpoint rather than into a screen tree. Nothing in the tree can count. |
| `consent checkbox` — free, `checkbox_input` exists, one more field on the number schema | **Right** about the mechanism. Not done, because what the box says is a legal decision and the canvas draws no copy for it. |

- **The decision and its reason.** Take the placeholder now — it is drawn, it is one argument, and it
  needs nothing from the client. Leave the rest until somebody wants the segmented control, because
  each remaining piece buys appearance rather than capability, and `code_input` is a wire type this
  product would then have to keep.
- **`mask` is refused rather than deferred**, and that is the one entry here that is a decision rather
  than a price. `Msisdn.parse` takes seven to fifteen digits from any country deliberately; a mask
  shaped like one country's number refuses every other. A white-label product whose sign-in field
  only accepts the numbers of the country it was designed in is broken for exactly the operator who
  bought it. The same objection applies to grouping the number on the profile screen.
- Not covered: the resend control, which needs the countdown; and any change to the two refusals,
  which are correct as they are.

- AC: the number field shows an example, and typing a number of another country still signs in.
- AC: if `code_input` is ever taken, it arrives with a renderer in the same change — the dictionary
  guard makes that unavoidable, and `bottom_nav` is the precedent.
- Anchors: `server/src/main/kotlin/io/konekt/login/LoginScreens.kt`,
  `docs/design/design-app-canvas.md`.

Background: [B-46](B-46-no-login-screen.md) built the screen these frames record.
