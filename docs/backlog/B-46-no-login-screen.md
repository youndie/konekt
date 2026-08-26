---
id: B-46
title: "Both runners sign in through a route that must never ship"
status: open
priority: P1
size: M
stage: stage-m3-product
epic: feature-authentication
---

# B-46 — Both runners sign in through a route that must never ship

`Main.kt` and `HomeEntryPoint.kt` both authenticate the same way: request an OTP, then read the code
back from `/api/v1/dev/otp`. Both files say what that is, in the same words — *"a machine endpoint
revealing any subscriber's code IS the authentication system"* — and both do it anyway, because there
is nowhere else to get a code.

So the application has no way in. The auth feature is complete on the server (OTP request, verify,
refresh on 401, logout, the session behind ktor's bearer plugin, all tested), and the client's session
holder is complete. What is missing is the screen between them: a number, a code, and the two refusals
a subscriber will actually meet — a wrong code and an expired one.

- **The decision and its reason.** The login screen is **server-built like every other**, which makes
  it the sharpest test of the whole claim: if a form with two steps, a countdown and two error states
  can be a server response, the boundary is where this product says it is. `kompot-forms` is already a
  dependency and `B-20` proved a patched form works, so this is a form and a `submit` endpoint rather
  than new machinery.
- The rejected alternative is a hand-written Compose login screen "just for the entry point". It is
  the one screen where hand-writing is most tempting and least defensible: the copy for a failed code
  is exactly the kind of string the server owns everywhere else.
- Not covered: any second factor, any social login, and rate limiting beyond what the OTP feature
  already does. Also not covered: remembering the number between launches.

- AC: the desktop runner opens on a login screen the server built, a subscriber types a number and a
  code, and reaches the home screen — with `DEV_REVEAL_OTP` **off**, so the code has to come from the
  SMSC mock's log rather than from a route.
- AC: a wrong code and an expired code each say so on the screen, in copy the server sent.
- AC: `Main.kt` and `HomeEntryPoint.kt` no longer reference `DevOtp`, asserted by a source guard — the
  same idiom as `RunCatchingUsageTest`, because a development route creeping back into an entry point
  is exactly what nobody would notice.
- Anchors: `client/src/jvmMain/kotlin/io/konekt/client/Main.kt`,
  `client/src/iosMain/kotlin/io/konekt/client/ios/HomeEntryPoint.kt`,
  `feature/auth-server-data/`, `server/src/main/kotlin/io/konekt/screens/`.

Background: [feature-authentication](../features/feature-authentication.md),
[B-20](B-20-custom-package-builder.md) for the form machinery this reuses.
