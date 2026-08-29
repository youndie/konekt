package io.konekt.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import io.konekt.client.observability.KonektCrashReporter

// A DELIBERATE CRASH FROM THE ANDROID BUILD — and the one thing in this repository that is WIRED AND
// KNOWN NOT TO ARRIVE. That is stated here, in the file, because the alternative is a harness that
// looks like the iOS one and quietly proves nothing.
//
// `B-27` closed the same question for iOS, and the story before it is why this exists at all: katcher
// published no Apple target until `client:0.6.2`, so the iOS build reported nothing and NOTHING SAID
// SO — a reporter that cannot be reached does nothing, exactly like one with nothing to report.
//
// WHAT WAS MEASURED HERE, on a Pixel 6a running this build (`B-85`, youndie/katcher#27):
//
//     E System     : Ignoring attempt to set property "user.dir" to value "/data/user/0/…/cache".
//     I System.out : 📡 Katcher initialized. Storage ready.
//     E AndroidRuntime: FATAL EXCEPTION: main … deliberate crash from the konekt Android build
//     I System.out : 📡 Failed to save crash report: /.katcher_cache/crash_….json: ENOENT
//
// The chain: katcher's multiplatform `client` publishes no android variant, so this build resolves
// `client-jvm`; its file system is fixed at `File(System.getProperty("user.dir"), ".katcher_cache")`,
// which on Android is `/` and unwritable; and Android REFUSES to let an application change `user.dir`,
// so there is no workaround from this side. The other artefact, `client-android`, declares the same
// `object Katcher` in the same package and cannot share a classpath with the one `:client`'s common
// code compiles against.
//
// The hook itself is fine — `Thread.setDefaultUncaughtExceptionHandler`, which Android honours, fires
// and reaches katcher's own `catch`. Everything works except the last step.
//
// SO THIS ACTIVITY IS THE HARNESS, KEPT AND HONEST. It crashes, the reporter runs, and the report is
// not stored — and the day the android variant lands, this is what proves it. `README.md`'s
// observability table carries the row as **not delivered** with this reason, rather than leaving a
// blank that reads like "not tried".
//
//     adb shell am start -n io.konekt.android/.CrashActivity \
//         --es KATCHER_URL <endpoint> --es KATCHER_KEY <key> --es KONEKT_RELEASE <release>
class CrashActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // THE SAME REFUSALS AS EVERY OTHER PLATFORM. `KonektCrashReporter` moved to `commonMain` in
        // `B-85` — a blank key or host throws here rather than producing a build that means to report
        // and silently does not. They are the refusals this repository CAN make; the one it cannot is
        // the library saying "Storage ready" over a directory it never checked.
        KonektCrashReporter.start(
            appKey = intent?.getStringExtra("KATCHER_KEY").orEmpty(),
            remoteHost = intent?.getStringExtra("KATCHER_URL").orEmpty(),
            release = intent?.getStringExtra("KONEKT_RELEASE") ?: "android-dev",
            environment = intent?.getStringExtra("KONEKT_ENV") ?: "development",
            debug = true,
        )

        error("deliberate crash from the konekt Android build, so a report exists to look for")
    }
}
