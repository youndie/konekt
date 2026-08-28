---
id: B-69
title: "\"1 eSIM installed\" over a number that counts profiles held, installed or not"
status: done
priority: P1
size: S
stage: stage-m4-proof
epic: feature-esim-lifecycle
---

# B-69 — The sentence claims more than the number knows

Profile, immediately after a purchase and with nothing installed on anything:

> 1 eSIM installed

The row behind it is `status = ready`. The model distinguishes the two perfectly well — there is an
`INSTALLED` status and a `markInstalled`, whose own comment insists that *"Installed is not active"*
— so the distinction is made everywhere except in the sentence a subscriber reads.

The number is `countHeldBy`, and its query says what it is for:

> A TERMINATED profile does not occupy a slot, and that is the whole content of this query.

It counts SLOTS. The parameter is even called `esimsHeld`. Only the copy calls it installed.

## The same number drives a control, so this is not only wording

Home renders its install banner when `esimsHeld == 0`. A subscriber who has bought a profile and
installed nothing therefore loses the banner — the entry point to the very wizard they still need.
Together with [B-66](B-66-the-esim-qr-is-unreachable-through-the-app.md) that leaves the eSIM
unreachable from the running app: no banner, and a wizard that will not show the code.

## What was done

**One question, one answer, three buckets.** `countHeldBy` is gone; `holdingsOf` returns
`EsimHoldings(held, awaitingInstall, installed)`. The buckets are the domain's and the STATUSES are
the data layer's — that module does not know the wire vocabulary, and which status string means "on a
device" is a fact about the table. The `when` that sorts them has no `else` that guesses: a status
added to the vocabulary lands in `held` and in neither of the other two, because guessing which is
exactly the mistake being replaced.

The old comment on the parameter was already right about the danger — *"two shapes of the same
question is how two screens come to disagree about it"* — and there were two shapes of it: a count of
slots, read once as a slot limit and once as an install count.

**The profile says what is on the line**, and states both numbers when both are non-zero: "1 eSIM
installed, 1 not installed yet". A total would be true and would hide the fact worth acting on. Not
"ready to install" either — a profile still being prepared is in the same bucket and is not ready for
anything; what is true of both is that neither is on a device.

**The home banner is open exactly while something is not on a device.** The condition was `held == 0`
under a heading that said *"something bought and not yet installed"*, so it appeared for a line with
no profile and vanished the moment one was issued. Two states, two sentences: a subscriber told "your
line has no eSIM yet" about a profile they have paid for would reasonably think the purchase failed.

**Guarded as tables over the whole space**, not as the case that was wrong:

| Guard | What it says |
|---|---|
| `HomeScreenTest` | the door is open for all four combinations it should be, shut for the one it should not, and the two open states do not share a sentence |
| `ProfileScreenTest` (new — nothing covered this screen at all) | the sentence matches the state, and a line with nothing on a device never says "installed" whatever else changes about the copy |
| `AppFrame - App home uninstalled` / `App profile uninstalled` | somebody looks at the state between paying and scanning |

The home banner guard was proved by mutation: restoring `held == 0` fails both of its tests.

Two frames rather than one because the defect was two screens disagreeing about one question, and
neither state had ever been photographed.

## Anchors

| What | Where |
|---|---|
| The sentence | `server/src/main/kotlin/io/konekt/screens/ProfileScreen.kt` (`esimLine`) |
| The number | `feature/esim-server-data/.../ExposedEsimRepository.kt` (`countHeldBy`) |
| The banner that reads it | `server/src/main/kotlin/io/konekt/screens/HomeScreen.kt` |
