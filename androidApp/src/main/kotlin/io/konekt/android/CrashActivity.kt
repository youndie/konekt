package io.konekt.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import io.konekt.client.observability.KonektCrashReporter

// A DELIBERATE CRASH FROM THE ANDROID BUILD, and the harness that measured the same gap twice: once
// when the report was lost, and once when it arrived.
//
// `B-27` closed the question for iOS, and the story before it is why this exists at all: katcher
// published no Apple target until `client:0.6.2`, so the iOS build reported nothing and NOTHING SAID
// SO — a reporter that cannot be reached does nothing, exactly like one with nothing to report.
//
// WHAT WAS MEASURED HERE FIRST, on a Pixel 6a (`B-85`, youndie/katcher#27):
//
//     E System     : Ignoring attempt to set property "user.dir" to value "/data/user/0/…/cache".
//     I System.out : 📡 Katcher initialized. Storage ready.
//     E AndroidRuntime: FATAL EXCEPTION: main … deliberate crash from the konekt Android build
//     I System.out : 📡 Failed to save crash report: /.katcher_cache/crash_….json: ENOENT
//
// The multiplatform `client` published no android variant, so this build resolved `client-jvm`, whose
// cache was `File(System.getProperty("user.dir"), ".katcher_cache")` — `/` on Android, and Android
// refuses to let an application change the property. The hook fired and the last step lost the
// report, under a "Storage ready" that had checked nothing.
//
// AND WHAT THE SAME LAUNCH MEASURED AFTER `client:0.6.41` closed the issue:
//
//     I System.out : 📡 Katcher initialized. Storage ready.
//     E AndroidRuntime: FATAL EXCEPTION: main … deliberate crash from the konekt Android build
//     I System.out : 📡 Report saved to disk. Signal sent.
//
// The build now resolves `client-android:0.6.41`, the android variant of the one client the shared
// code compiles against; its cache is the application's `cacheDir`, handed over by a `ContentProvider`
// in the library's own manifest, and `start` refuses when it cannot write there. The report lands in
// `cache/katcher_cache`, and the collector shows it under this build's release. `README.md`'s
// observability row is **delivered**, with this file as the measurement.
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

        // `--es KONEKT_CRASH false` STARTS THE REPORTER AND STAYS ALIVE. katcher uploads what the cache
        // holds from `start`, on a background coroutine; a process that throws two milliseconds later
        // is gone before the upload leaves, so a crash alone proves storage and never delivery. The
        // second launch, without the crash, is the one that empties `cache/katcher_cache` — and that
        // emptying is what the README's row claims.
        if (intent?.getStringExtra("KONEKT_CRASH") == "false") return

        error("deliberate crash from the konekt Android build, so a report exists to look for")
    }
}
