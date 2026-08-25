---
id: B-17
title: "The eSIM order wizard, and an SM-DP+ mock that can be out of slots"
status: open
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

- **The decision and its reason.** `kompot-wizard` for the flow, because the step machine is
  `(session, transition, draft) -> session` and branching is therefore unit-testable with no HTTP, no
  database and no UI. The activation code is generated as an `LPA:1$rsp.konekt.io$…` string and turned
  into a QR **on the client**, so a code never becomes an image on the server.
- The rejected alternative is a server-rendered QR image. It puts the activation code through
  `kompot-images` and a URL, which is a credential in a query string.
- Not covered: talking to a real eSIM stack. Installation is imitated; the canvas's install copy says
  what a real one would say and the button does not call the OS.

- AC: the slot-limit refusal reaches step 1 with the canvas's copy and the wizard does not advance.
- AC: a successful order ends with a scannable QR whose payload is the issued activation code.
- Anchors: `server/src/main/kotlin/io/konekt/esim/`, `server/src/main/kotlin/io/konekt/mocks/smdp/`,
  `client/src/commonMain/kotlin/io/konekt/render/EsimQrRenderer.kt`.

Background: [design-app-canvas](../design/design-app-canvas.md) section 04.
