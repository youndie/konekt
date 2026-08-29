package io.konekt.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
            // NO INSET HANDLING HERE, deliberately, and it was here for one build.
            //
            // Measured on a Pixel 6a: without any insets the first element of every screen draws
            // UNDER the status bar — the login title read as a damaged font and the home title as a
            // clipped logo, while everything below them was perfect. Wrapping the frame in
            // `windowInsetsPadding` fixed that and broke something quieter: it inset the GROUND as
            // well, so the system bars showed the platform theme's window background instead of the
            // brand's surface. Both are only visible in a screenshot.
            //
            // So the padding lives inside `KonektApp`, under the background and over the content,
            // where the design system's colour can be asked for. This activity draws nothing.
            composition.Screen()
        }
    }
}
