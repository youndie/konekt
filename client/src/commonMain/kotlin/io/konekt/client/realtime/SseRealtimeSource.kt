package io.konekt.client.realtime

import io.github.youndie.kompot.realtime.KompotRealtimeSource
import io.github.youndie.kompot.realtime.UpdateComponentMessage
import io.konekt.feature.realtime.shared.api.RealtimeStream
import io.ktor.client.HttpClient
import io.ktor.client.plugins.sse.serverSentEvents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import ru.workinprogress.katcher.Katcher
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// The transport kompot refuses to choose, chosen: Server-Sent Events.
//
// `KompotRealtimeSource` is a one-method contract returning a Flow of frames, and everything below
// is what it takes to make that Flow survive a network. There is no `ktor-client-sse` artefact —
// the client plugin lives in `ktor-client-core`, which is easy to assume otherwise from the server
// side, where `ktor-server-sse` IS one.
class SseRealtimeSource(
    private val client: HttpClient,
    private val json: Json,
    private val path: String = RealtimeStream.PATH,
    private val backoff: Backoff = Backoff(),
) : KompotRealtimeSource {
    // Emitted every time the stream comes back after a break.
    //
    // THIS IS THE HONEST REPLACEMENT FOR `Last-Event-ID`, and the reasoning is worth keeping. That
    // header resumes a stream by replaying what was missed, which needs the server to number its
    // frames and keep them — and this server does neither, deliberately: an update is losable by
    // design, because the client gets current state with its next screen request. So there is
    // nothing to resume against, and a client that sent the header would be asking a question the
    // protocol here cannot answer.
    //
    // What a screen actually needs after a gap is not the frames it missed but the state it is in
    // now. This signal says "you have a gap"; refetching is the screen's business.
    private val restarts = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    val streamRestarted: SharedFlow<Unit> get() = restarts

    // THE TOPIC IS IGNORED, and that is the server refusing to be told. The stream carries one
    // subscriber's updates and the subscriber is taken from the verified token — a stream addressed
    // by a parameter is every subscriber's screen for anybody who asks. The parameter stays in the
    // signature because it is the toolkit's contract, and a client that one day talks to a server
    // with several channels will need it.
    override fun subscribe(topic: String): Flow<UpdateComponentMessage> =
        // `channelFlow` and not `flow`, because the frames are produced inside ktor's own session
        // block: a plain flow's `emit` may only be called from the collector's coroutine, and
        // emitting from someone else's is a runtime "flow invariant is violated" rather than a
        // compile error.
        channelFlow {
            var attempt = 0
            var everConnected = false

            while (isActive) {
                try {
                    client.serverSentEvents(urlString = path) {
                        attempt = 0
                        if (everConnected) restarts.emit(Unit)
                        everConnected = true

                        incoming.collect { event ->
                            val data = event.data ?: return@collect
                            // A frame that does not PARSE is dropped rather than fatal — a malformed
                            // line takes one update rather than the stream, and taking the stream
                            // down would lose every later one too.
                            //
                            // A frame naming a component this build has never heard of is NOT this
                            // case and must not be: it decodes to UnknownComponent and is delivered,
                            // so the screen draws the unknown block. That is kompot's degradation
                            // working, and dropping it here would quietly turn "draw a placeholder"
                            // into "lose the update".
                            val message =
                                try {
                                    json.decodeFromString(UpdateComponentMessage.serializer(), data)
                                } catch (malformed: SerializationException) {
                                    // The precise type rather than a `runCatching`: this catches a
                                    // line that is not the protocol, and nothing else. A broad catch
                                    // here would also swallow the cancellation that ends the stream.
                                    null
                                }

                            message?.let { send(it) }
                        }
                    }
                } catch (cancellation: CancellationException) {
                    // The collector went away. Not a network failure and must not be retried, or the
                    // loop outlives the screen that started it.
                    throw cancellation
                } catch (failure: Exception) {
                    // Everything else is retried, and the retry is the whole point of this class: a
                    // closed laptop, a proxy timing out, a server rolling. What is NOT true is that
                    // those are the only things that land here — an expired token, a 400 the server
                    // will answer identically for ever, and a TLS failure retry on the same backoff
                    // and for as long as the screen is open. Whether some of them should stop the
                    // loop is a real question and a larger one than this line.
                    //
                    // THIS LINE IS THE SMALLER HALF: whatever it was, it is named once per attempt.
                    // A breadcrumb rather than a log, for the reason `KonektClientObservability`
                    // gives for putting the breadcrumb first — it is an in-memory append that
                    // katcher attaches to the NEXT crash, it needs no agent to be configured and no
                    // dependency this class does not already have, and a stream that reconnected
                    // forty times before something else fell over is exactly the context a crash
                    // report cannot reconstruct afterwards.
                    Katcher.addBreadcrumb(
                        message = "realtime stream failed: ${failure::class.simpleName}",
                        type = "realtime",
                        data =
                            mapOf(
                                "attempt" to attempt.toString(),
                                "everConnected" to everConnected.toString(),
                                // The message and not the stack: a breadcrumb is a line in a list a
                                // person reads beside a crash, and a Kotlin/Native stack in it would
                                // push the other breadcrumbs off the screen.
                                "failure" to (failure.message ?: failure::class.simpleName ?: "unknown"),
                            ),
                    )
                }

                delay(backoff.after(attempt))
                attempt += 1
            }
        }

    // Doubling, capped, from a short first wait.
    //
    // No jitter, and that is a decision rather than an omission: this product has one stream per
    // subscriber and a server rolling reconnects a handful of clients, not a thundering herd. Jitter
    // is what a fleet needs, and adding it here would be machinery answering a problem this build
    // does not have.
    class Backoff(
        private val first: Duration = 1.seconds,
        private val ceiling: Duration = 30.seconds,
    ) {
        fun after(attempt: Int): Duration {
            val doubled = first * (1 shl attempt.coerceAtMost(MAX_DOUBLINGS))
            return if (doubled > ceiling) ceiling else doubled
        }

        private companion object {
            // 2^5 × one second is already past the ceiling; the cap exists so the shift cannot
            // overflow on a stream that has been retrying for a day.
            const val MAX_DOUBLINGS = 5
        }
    }
}
