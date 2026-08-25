package io.konekt.time

import ru.workinprogress.petich.PetichClock

// petich takes its own one-method clock, in epoch millis, because a deadline is compared across rows
// in a database and survives a process restart — a monotonic mark would mean nothing there. Adapting
// rather than binding a second clock is what keeps the saga sweeper and the domain on one notion of
// now: a test that moves time moves it for both.
//
// Here rather than beside KonektClock because petich is a server dependency, and the shared domain
// compiles for iOS.
fun KonektClock.asPetichClock(): PetichClock = PetichClock { now().toEpochMilliseconds() }
