---
id: B-27
title: "Write down that the iOS build reports no crashes"
status: open
priority: P1
size: XS
stage: stage-m4-proof
blocked_by: [B-26]
---

# B-27 — Write down that the iOS build reports no crashes

`katcher:client:0.5.1` publishes two variants and the module metadata names them: `jvm` and
`linux_x64`. Android is a separate coordinate. No Apple target is declared anywhere in the client
module (research §1.9). So the iOS half of this application reports nothing, and the only thing worse
than that is it being true and unwritten.

- **The decision and its reason.** State it in the service document and in the README rather than
  adding a different vendor's SDK. The purpose of this build is to exercise this stack; an answer
  borrowed from elsewhere would hide the finding, which is the finding's whole value. Raised upstream
  as [U5](../research/research-upstream-proposals.md#u5).
- The rejected alternative, Crashlytics on iOS only, gives crash coverage and gives up the ability to
  say what this stack does and does not cover.
- Not covered: closing the gap. That depends on the upstream answer.

- AC: the service document names the gap, its cause and the issue tracking it.
- AC: the README's observability section does not imply iOS coverage.
- Anchors: `docs/services/konekt-app.md`, `README.md`.

Background: [research-architecture](../research/research-architecture.md) §1.9, D8.
