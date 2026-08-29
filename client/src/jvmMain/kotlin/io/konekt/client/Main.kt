package io.konekt.client

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import io.konekt.client.app.KonektComposition
import io.konekt.client.app.KonektPlatform
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

// THE DESKTOP APPLICATION, and it is now four lines of platform around a shared composition root.
//
// It no longer knows a development route exists. It used to sign in through `/api/v1/dev/otp` — the
// endpoint that reads back a one-time code — because there was nowhere else to get one: a machine
// endpoint revealing any subscriber's code IS the authentication system, and this file did it anyway.
// `B-46` built the login screen, so the way in is now the way in.
//
// Everything that used to be here — the session, the observability, the action handling — is
// `KonektComposition`, written once for three platforms after the copies drifted (`B-85`).
//
//     make stand-up && ./gradlew :client:run
fun main() {
    val composition =
        KonektComposition(
            engine = CIO.create(),
            settings = System::getenv,
            platform =
                KonektPlatform(
                    name = "konekt-desktop",
                    defaultBaseUrl = "http://127.0.0.1:8080",
                    defaultRelease = "desktop-dev",
                    topicKey = "konekt-session",
                ),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
    composition.start()

    application {
        Window(onCloseRequest = ::exitApplication, title = "konekt") {
            composition.Screen()
        }
    }
}
