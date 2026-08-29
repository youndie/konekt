package io.konekt.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import io.konekt.client.observability.KonektCrashReporter

// A DELIBERATE CRASH FROM THE ANDROID BUILD, so the claim "katcher reports this client" is made on
// the platform rather than about it.
//
// `B-27` closed the same question for iOS, and the story before it is why this exists at all: katcher
// published no Apple target until `client:0.6.2`, so the iOS build reported nothing and NOTHING SAID
// SO — a reporter that cannot be reached does nothing, exactly like one with nothing to report.
//
// Started by hand and reachable no other way:
//
//     adb shell am start -n io.konekt.android/.CrashActivity \
//         --es KATCHER_URL <endpoint> --es KATCHER_KEY <key> --es KONEKT_RELEASE <release>
class CrashActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // THE WORKAROUND, AND IT IS THE ANDROID FINDING OF `B-85` — youndie/katcher#26.
        //
        // This build uses katcher's MULTIPLATFORM client on Android, because `client-android` declares
        // the same `object Katcher` in the same package and the two cannot share a classpath. The KMP
        // client publishes no android variant, so what resolves is `client-jvm` — which links, hooks
        // `Thread.setDefaultUncaughtExceptionHandler` (which Android honours), and then writes its
        // stored reports to `File(System.getProperty("user.dir"), ".katcher_cache")`.
        //
        // On Android `user.dir` is `/`, which is not writable. `saveReport` throws, the library
        // catches it and prints, and the upload is never signalled: a crash reporter that is
        // correctly configured and structurally unable to report — the exact silence this repository
        // has now met four times.
        //
        // The property is set before the reporter starts because the library reads it once, when its
        // file system is first touched. `cacheDir` is this application's own and is what an Android
        // library would have used.
        System.setProperty("user.dir", cacheDir.absolutePath)

        // THE SAME REFUSALS AS EVERY OTHER PLATFORM. `KonektCrashReporter` moved to `commonMain` in
        // this item — a blank key or host throws here rather than producing a build that means to
        // report and silently does not.
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
