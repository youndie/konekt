package io.konekt.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier
import io.konekt.client.app.KonektComposition
import io.konekt.client.app.KonektPlatform
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

// THE ANDROID HALF OF THE COMPOSITION ROOT, and it is the same root: `KonektComposition` is what the
// desktop and iOS runners hold, so a screen drawn here is drawn by the registry they draw with.
//
// What Android differs in is three things and they are the three parameters: an engine that carries
// the device's trust store, settings that come from an intent because a phone has no environment, and
// `setContent` instead of a window or a view controller.
class KonektActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // SETTINGS FROM THE LAUNCH INTENT, which is the honest Android equivalent of an environment
        // variable: it is what `adb shell am start --es KONEKT_URL http://10.0.2.2:8080` sends, so a
        // stand run and a device run are the same command with a different address.
        //
        // Not `BuildConfig`, because a value compiled into the APK cannot be changed without a build,
        // and pointing this application at a different stand is exactly the thing done most often.
        val composition =
            KonektComposition(
                engine = OkHttp.create(),
                settings = { name -> intent?.getStringExtra(name) },
                platform =
                    KonektPlatform(
                        name = "konekt-android",
                        // 10.0.2.2 IS THE HOST FROM INSIDE THE EMULATOR — the Android equivalent of
                        // the simulator's `127.0.0.1`, and wrong on a real phone, which has a route to
                        // neither. That is why it is a default and `KONEKT_URL` is read first.
                        defaultBaseUrl = "http://10.0.2.2:8080",
                        defaultRelease = "android-dev",
                        topicKey = "konekt-android",
                    ),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            )
        composition.start()

        setContent {
            // THE INSETS, AND THEY ARE THE ONE THING THIS PLATFORM NEEDS THAT THE OTHERS DO NOT.
            //
            // Measured on a Pixel 6a rather than reasoned about: without this the first element of
            // every screen draws UNDER the status bar. The failure is not a blank screen and not an
            // error — it is the top few pixels of one line of text, so the login title read as a
            // damaged font and the home title as a clipped logo, while everything below them was
            // perfect. A screenshot is the only way that is visible at all.
            //
            // `safeDrawing` rather than `statusBars`: it also covers the gesture bar at the bottom
            // and a display cutout, and this application draws a bottom bar.
            Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                composition.Screen()
            }
        }
    }
}
