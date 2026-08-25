package io.konekt.client.observability

import ru.workinprogress.katcher.Katcher

// CRASH REPORTING FOR THE APPLE BUILD, which reported nothing until now — and not because nobody had
// wired it. `katcher:client:0.5.1` published `jvm` and `linux_x64` and no Apple target at all, so the
// iOS half of this application had no reporter it could depend on. That was filed as U5 and
// youndie/katcher#25 closed it: `0.6.2` publishes `ios_arm64`, `ios_simulator_arm64` and `ios_x64`,
// alongside both macOS targets, both Linux ones, `mingw_x64` and `jvm`.
//
// WHAT DOES NOT NEED WRITING, and B-27 expected it to. The item says the uncaught-exception hook is
// "the part to check by actually crashing the app", on the grounds that Kotlin/Native's
// `setUnhandledExceptionHook` is a different mechanism from the JVM's
// `Thread.UncaughtExceptionHandler` and does not come free with a target declaration. It does not
// come free with the target — it comes free with `Katcher.start()`. The library's `nativeMain` has an
// `internal actual fun setupPlatformHandler()` that installs the hook, keeps whatever hook was there
// before, calls it afterwards, and falls back to `terminateWithUnhandledException` when there was
// none. Read in the sources of 0.6.2 rather than assumed.
//
// So what is left for this file is the part the library cannot do: refuse to start wrong.
object KonektCrashReporter {
    // THE REFUSAL, and it is the whole reason this is a function rather than a call at a call site.
    //
    // `Katcher.start` answers a missing appKey or host by printing a line and returning. That is the
    // shape of failure this repository keeps meeting: an observability agent that is switched off is
    // indistinguishable from one that is working, because both produce silence — and the silence is
    // discovered when somebody goes looking for a crash that was never reported. A build that means
    // to report and cannot must fail where it is configured, not where it crashes.
    //
    // The switch is deliberate and separate: a caller that does not want reporting says so by not
    // calling this, and one that does gets an exception if it cannot.
    fun start(
        appKey: String,
        remoteHost: String,
        release: String,
        environment: String,
        debug: Boolean = false,
    ) {
        require(appKey.isNotBlank()) { "katcher appKey is blank — the iOS build would report nothing and say nothing" }
        require(
            remoteHost.isNotBlank(),
        ) { "katcher remoteHost is blank — the iOS build would report nothing and say nothing" }
        // Not defaulted. `KatcherConfig.release` defaults to the string "Unspecified", and a crash
        // group that cannot say which build produced it is a crash group nobody can act on — the AC
        // of B-27 is a report NAMING the release for that reason.
        require(
            release.isNotBlank(),
        ) { "katcher release is blank — a crash that cannot name its build is not actionable" }

        Katcher.start {
            this.appKey = appKey
            this.remoteHost = remoteHost
            this.release = release
            this.environment = environment
            this.isDebug = debug
        }
    }

    // Reporting a throwable this application caught itself. The hook covers what escapes; this covers
    // what does not — a failure swallowed by a `runCatching` at a screen boundary is invisible to the
    // hook by construction, and those are the ones a subscriber reports as "it did nothing".
    fun report(
        throwable: Throwable,
        context: Map<String, String> = emptyMap(),
    ) {
        Katcher.catch(throwable, context)
    }
}
