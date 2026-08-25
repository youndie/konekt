---
id: B-20
title: "The custom package builder as a form, with the price coming from the server"
status: open
priority: P2
size: M
stage: stage-m3-product
epic: feature-buy-package
blocked_by: [B-08]
---

# B-20 — The custom package builder as a form, with the price coming from the server

Three quantities — gigabytes, minutes, messages — and a price that changes as they move. This is the
one screen where `form-core`'s split earns its keep: validation, visibility and cross-field rules run
on the client, and only a server-relevant change asks the backend for a patch.

- **The decision and its reason.** Bounds and steps are validated locally; the price is a patch from
  the server, because a price computed on the client is a price a client can argue with. kompot's
  readme is explicit that limits and balances belong to the server and the client only highlights the
  field it names.
- The rejected alternative is sending a price table to the client. It is fewer round trips and it puts
  the tariff in the app bundle, where changing it is a release.
- Not covered: promotional pricing. One tariff function, no campaign layer.

- AC: moving a slider updates the price without the fields losing focus or resetting.
- AC: a combination the balance cannot cover is refused by the server and the balance field is the one
  highlighted.
- Anchors: `server/src/main/kotlin/io/konekt/packages/CustomPackageForm.kt`.

Background: [research-architecture](../research/research-architecture.md) §1.5.
