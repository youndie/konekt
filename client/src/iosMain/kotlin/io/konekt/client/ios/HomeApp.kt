package io.konekt.client.ios

import androidx.compose.ui.window.ComposeUIViewController
import io.konekt.client.app.KonektComposition
import io.konekt.client.app.KonektPlatform
import io.ktor.client.engine.darwin.Darwin
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import platform.Foundation.NSProcessInfo
import platform.UIKit.UIViewController

// THE APPLE HALF OF B-43'S COMPOSITION ROOT: an iOS application that draws the home screen the SERVER
// built, through the same holder, source and registry the desktop runner uses.
//
// It is now literally the same one. This file used to carry its own copy of the root, and the copy
// had drifted: it handled `update_session` and NOT `SignOutAction`, so signing out worked on a laptop
// and printed "no handler" on a phone. `B-85` moved the root into `KonektComposition` while adding
// the third platform, which is the point at which a third copy would have been written.
//
// What is left here is what iOS actually differs in: an engine that exists on Apple targets,
// `NSProcessInfo` instead of an environment, and Compose's UIKit host so a `@Composable` can be put
// inside a `UIViewController`.
@OptIn(ExperimentalForeignApi::class)
fun homeViewController(): UIViewController {
    val env = NSProcessInfo.processInfo.environment

    val composition =
        KonektComposition(
            engine = Darwin.create(),
            settings = { name -> env[name] as? String },
            platform =
                KonektPlatform(
                    name = "konekt-ios",
                    // ON THE SIMULATOR, `127.0.0.1` IS THE HOST MAC. That is why this needs no address
                    // of its own by default and why a device would: a phone on a desk has no route to
                    // a laptop's loopback.
                    defaultBaseUrl = "http://127.0.0.1:8080",
                    defaultRelease = "ios-dev",
                    topicKey = "konekt-ios",
                ),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
    composition.start()

    return ComposeUIViewController { composition.Screen() }
}
