---
id: B-50
title: "The login screen the canvas draws last is four additions, and two of them are not what they look like"
status: done
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

## What landed, and the price that was wrong in the other direction

The item priced four additions and this closes on three of them — but the reason is not the pricing.
Building any of it meant looking at the screen, and the screen had a hole none of the four described:
**there was no way to ask for a new code, and no way off the screen at all.**

The code step REPLACES the login step — the submit answers a `navigate`, which is a step rather than a
push — so there is no back control either. A subscriber whose message never arrived, or who mistyped a
digit, had exactly one option: close the application. That is worth more than every appearance
question the frame raises, and nothing in the item mentioned it.

**`Send a new code` and `Use a different number`**, both quiet beside `Sign in`, which is the emphasis
`B-58` made drawable.

## The countdown cost nothing, because the refusal already knew the number

The item priced it as "no clock in the payload; the cheapest honest version is a bound
`read_only_field` the server refreshes". That was still thinking in clocks. `RequestOtpUseCase`
already answers `RateLimited(secondsLeft)` — asking again and being told is the same information with
no timer, no poll and no wire type:

> A code was sent already. You can ask for another in 42 seconds.

The seconds travel as a NUMBER in the query and the sentence is composed on the server, which is the
rule the wrong-code refusal already follows: a link is something anybody can hand somebody, and the
worst a crafted one can do here is change a figure. Zero or nonsense degrades to the sentence without
one.

## It had to become a verb, and the first attempt proved why

`SubmitFormAction(NUMBER_FORM)` looked free — the number is already a bound field on this form, so
submitting these values under the first form's id posts exactly what step one posts. **The toolkit
intercepts a `submit_form` only for the form its screen HOLDS**, so the button fell through to the
runner, matched nothing, and posted nothing: a control that looks pressed and does not work, which is
the shape of the defect it was added to fix. One OTP in the server log where two were expected is what
said so.

So `resend_code` is the fifth verb, handled by the runner like `buy_plan`, `confirm_purchase`,
`sign_out` and `esim_wizard_step`. `feature/auth-shared-api` gained a kompot dependency to carry it —
it was the only feature wire module without one, because signing in had no verb of its own until now.

## Still refused, and now for a better reason

- **`code_input`** — the segmented control. A wire type this product would then have to keep, bought
  for appearance. The formatting half is `mask` plus `uppercase` and is free; the control is not, and
  nothing about the flow needs it.
- **The consent checkbox** — mechanically one field, and what it SAYS is a legal decision the canvas
  draws no copy for. Unchanged.
- **The prefix slot** — was never needed: the grey number in an empty field is a `placeholder`, which
  exists and shipped in `v0.1.4`.
- **`mask`** stays refused outright, and that is the entry here that is a decision rather than a price:
  `Msisdn.parse` takes seven to fifteen digits from any country deliberately, and a white-label
  product whose sign-in field only accepts one country's numbers is broken for exactly the operator
  who bought it.
