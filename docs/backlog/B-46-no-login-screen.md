---
id: B-46
title: "Both runners sign in through a route that must never ship"
status: done
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

## What landed

Two server-built forms — a number, then the code and the number it was sent to — and two `submit`
endpoints answering a `KompotAction` the client feeds back into the chain it already has: a `navigate`
after the first step, an `update_session` after the second. No new endpoint kind, no machinery of its
own; that is the toolkit's design and the whole reason a login screen can be a server response.

**Neither runner knows a development route exists.** `EntryPointsDoNotUseDevRoutesTest` reads both
files and refuses `DevOtp` and any `/api/v1/dev/` path, because reaching for it again is one import
and a green build.

- AC MET: the desktop and iOS runners both open on the login screen, and a subscriber types a number
  and a code to reach home. Watched on a phone: "Sign in", a number field and a pill button, every
  string composed by the server.
- AC MET: a wrong code says so, in copy the server owns. `LoginStandTest` types a wrong one FIRST,
  because a refusal is the half a happy path never exercises.
- AC MET: the source guard, and it stripped comments after its own first run tripped on a sentence
  explaining what the file USED to do.
- **The code comes from the SMSC mock's log**, not from `/api/v1/dev/otp`. This is the one suite that
  cannot use that route, because it exists to prove the application no longer needs it.

## The session lands in the runner, not the holder

`UpdateSessionAction` reaches `onAction`, the runner adopts the tokens and answers with the home
address. A holder that adopted a session would be a screen holder that knows what a token is —
the same boundary that keeps buying out of it.

## Four defects, and two are worth more than the feature

**A refusal travelled as a SENTENCE in the URL.** It has spaces, so the request line was malformed and
the client reported `Unsupported HTTP version: code` — naming neither the address nor the cause. The
deeper problem is the one the spaces hid: anybody who can hand somebody a link could put arbitrary
text on this product's login screen. The query carries a refusal CODE now, from a closed list, and the
sentence is composed on the server where D15 puts it. An unknown word draws no banner rather than an
empty one.

**A decoder's error named nothing.** "Unexpected JSON token at offset 13" is what sent the search in
the wrong direction for three rounds; the address and the first 200 bytes of the body are in the
message now, and that is what turned the next failure into one line.

**`submit_form` was not registered on either side** — the THIRD time a hand-registered action has cost
something in this build. A form's components are generated into `generatedFormsSerializersModule` and
its action is not, so a login screen carrying a submit button was a 500 on the server and would have
decoded to nothing on the client.

**The code screen refused to exist without a number.** A required query parameter made it unreachable
to anything that had not just come from step one — including the conformance walk, which asks for
every GET it can see. It defaults to empty and answers step one, which is also what a stale link
should do.
