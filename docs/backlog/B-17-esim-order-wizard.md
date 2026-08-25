---
id: B-17
title: "The eSIM order wizard, and an SM-DP+ mock that can be out of slots"
status: done
priority: P1
size: L
stage: stage-m2-live
epic: feature-esim-lifecycle
blocked_by: [B-08]
---

# B-17 — The eSIM order wizard, and an SM-DP+ mock that can be out of slots

Canvas section 04: four steps plus the QR frame in dark. The designer's note names the failure the
flow actually hits in the field — the eight-profile limit on step 1 — which means the SM-DP+ mock has
to refuse for that reason specifically rather than merely fail.

- **The decision and its reason.** `wizard-core` for the flow, because the step machine is
  `(session, transition, draft) -> session` and branching is therefore unit-testable with no HTTP, no
  database and no UI. The activation code is generated as an `LPA:1$rsp.konekt.io$…` string and turned
  into a QR **on the client**, so a code never becomes an image on the server.
- The rejected alternative is a server-rendered QR image. It puts the activation code through
  `kompot-images` and a URL, which is a credential in a query string.
- Not covered: talking to a real eSIM stack. Installation is imitated; the canvas's install copy says
  what a real one would say and the button does not call the OS. No money moves either — a paid line
  is the purchase feature's business.

- AC OK: the slot-limit refusal reaches step 1 and the wizard does not advance. The refusal travels in
  the view rather than as an exception, so what comes back is the same step with a banner on it —
  meter still reading 1 of 4 — and not a 409 with no screen behind it. `AdvanceEsimWizardUseCaseTest`
  and `EsimWizardRoutingTest` assert both halves, and both fail when the gate is removed.
- AC OK: a successful order ends with a QR whose payload is the issued activation code. Asserted from
  the two ends separately — the `esim_qr` on the screen against the `activation_code` column — so a
  screen that composed its own string would fail.
- AC OK (client half): `EsimQrRenderer` encodes the activation code and draws it. The library is an
  **encoder** rather than a QR widget — it answers a matrix and draws nothing — so the card around the
  code is the design system's while the code itself stays black on white, which is the one place this
  renderer refuses the theme: a camera reads through a contrast threshold, and a tasteful
  low-contrast pair is a code that scans on a designer's screen and not in a hotel corridor.
- Also: the encoding is asserted structurally rather than by looking at it — the size is a real QR
  version, the three finder patterns are in their corners, and the timing pattern alternates. A grid
  of the right dimensions that encodes the wrong thing looks exactly like one that does not.
- Also: a payload too large answers an empty matrix rather than throwing, and the screen then shows
  the caption and the typed code. That is what `manualCodeText` is carried for.

**Deviation from this item as written.** It said `kompot-wizard`, and the flow uses `wizard-core`
alone. `WizardScreenComponent` requires a `formId` that must name a real `FormSchema`, because a
client's wizard renderer builds its own Back and Next actions from it — and these four steps have no
form. The chrome is `step_meter` instead, which is in the dictionary for exactly this and which the
canvas describes as "the wizard's own progress". The form-shaped wire half becomes the right tool at
`B-20`. See [research-architecture](../research/research-architecture.md) §1.12.

**Deviation from the anchors as written.** They pointed at `server/.../esim/` and
`server/.../mocks/smdp/`. A feature is a vertical of modules rather than a package in `:server`
(CLAUDE.md), and the mock is a port implementation like `MockPaymentGateway`, which lives with its
feature. Both moved.

Two notes worth keeping. **konekt's one action is registered by hand**, because KSP generates
component registrations and nothing generates action ones — an application that omits the module
compiles, starts and draws every screen, and fails only when somebody presses Continue
([research-architecture](../research/research-architecture.md) §1.13). And **a profile is issued on
the way into `activate`, exactly once**: the draft carries the id, because a client arrives there more
than once — a Back and a Next, a retried request, a double tap — and issuing is the one step of this
flow that costs something outside the process.

- Anchors: `feature/esim-shared-api/src/commonMain/kotlin/io/konekt/feature/esim/shared/api/`,
  `feature/esim-server-domain/src/main/kotlin/io/konekt/feature/esim/server/domain/`,
  `feature/esim-server-data/src/main/kotlin/io/konekt/feature/esim/server/data/`,
  `shared/db/src/main/resources/db/migration/V8__esim_wizard_session.sql`.

Background: [design-app-canvas](../design/design-app-canvas.md) section 04;
[research-architecture](../research/research-architecture.md) §1.12, §1.13.
