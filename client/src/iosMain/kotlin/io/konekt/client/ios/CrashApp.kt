package io.konekt.client.ios

import io.konekt.client.observability.KonektCrashReporter
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSProcessInfo
import platform.posix.usleep
import ru.workinprogress.katcher.Katcher

// AN IOS APPLICATION WHOSE WHOLE JOB IS TO CRASH, and B-27 has no way to be met without one.
//
// The item's acceptance is "a deliberate crash in the iOS build produces a report in katcher naming
// the release". Everything under it was already true — katcher publishes every Apple target since
// `client:0.6.2`, `KonektCrashReporter` refuses to start half-configured, and its three cases run on
// the simulator. What was missing was an application: `:client` is a library, and a library cannot
// crash on a device.
//
// NO UI, DELIBERATELY. A screen would make this a second client to keep in step with the desktop one,
// and none of what is being proved is visual: that the reporter starts on a real Apple target, that
// Kotlin/Native's unhandled-exception hook fires, and that the report reaches a collector over the
// network from inside a simulator. A window would add a way for the test to fail that has nothing to
// do with any of them.
//
// It reads its configuration from the ENVIRONMENT rather than from constants, so the same binary is
// pointed at a stand or at nothing without being rebuilt — and so a run that forgot to configure it
// fails at the reporter's own refusal rather than by reporting into the void.
@OptIn(ExperimentalForeignApi::class)
fun main() {
    val env = NSProcessInfo.processInfo.environment

    fun setting(name: String): String = (env[name] as? String).orEmpty()

    val appKey = setting("KATCHER_KEY")
    val host = setting("KATCHER_ENDPOINT")
    val release = setting("KONEKT_RELEASE")

    println("konekt-crash: starting reporter for release '$release' against '$host'")

    // The refusals are the point of `KonektCrashReporter` and they are not bypassed here: a run with
    // a blank key throws from this line, which is a failure that names itself. `Katcher.start` on its
    // own answers the same mistake with a printed line and a return.
    KonektCrashReporter.start(
        appKey = appKey,
        remoteHost = host,
        release = release,
        environment = setting("KONEKT_ENVIRONMENT").ifBlank { "simulator" },
        debug = true,
    )

    // A breadcrumb before the crash, because that is the ordering a crash report exists to preserve:
    // katcher attaches the crumbs it holds to the NEXT report, so one left after the throw would
    // never appear.
    Katcher.addBreadcrumb(message = "about to fail on purpose", type = "test")

    // A PAUSE BEFORE THE CRASH, AND IT IS THE WHOLE REASON A SECOND RUN SEES ANYTHING.
    //
    // A crash reporter cannot upload the crash it is reporting: the process is dying. katcher saves
    // to disk on `catch` and uploads on the NEXT start, which is what every crash reporter does and
    // what makes them work at all. This binary has no run loop — `main` throws and the process is
    // gone — so without a wait here the uploader wakes, finds the previous report, and is killed
    // mid-request. Measured rather than assumed: two runs in a row left two reports on disk and
    // delivered neither, with "Worker woke up. Checking disk..." printed both times.
    //
    // A real application does not need this. Its run loop outlives the launch by minutes.
    val settle = setting("KONEKT_UPLOAD_WAIT_MS").toLongOrNull() ?: DEFAULT_UPLOAD_WAIT_MS
    println("konekt-crash: giving the uploader ${settle}ms for anything the last run left")
    usleep((settle * 1000L).toUInt())

    println("konekt-crash: throwing")
    throw IllegalStateException("deliberate crash from the konekt iOS build, for a report to exist at all")
}

// Long enough for one HTTP round trip to a collector on the same machine, and short enough that a run
// which delivers nothing fails quickly rather than looking like a hang.
private const val DEFAULT_UPLOAD_WAIT_MS = 4_000L
