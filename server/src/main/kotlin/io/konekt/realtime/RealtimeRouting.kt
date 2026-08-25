package io.konekt.realtime

import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.realtime.UpdateComponentMessage
import io.github.youndie.kompot.realtime.server.KompotUpdateBroadcaster
import io.konekt.http.subscriberId
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.sse.ServerSSESession
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.Json
import org.koin.ktor.ext.inject

// SSE, and not WebSocket.
//
// The traffic is one-directional — an UpdateComponentMessage goes server to client and nothing comes
// back — so the half of a WebSocket that lets a client send is a half nobody uses and everybody has
// to reason about. SSE also survives the proxies that mishandle an upgrade, and its reconnection
// carries `Last-Event-ID` in the protocol rather than in our code.
//
// kompot ships the frame contract and refuses to choose a transport. This is the choice.
private const val SUBSCRIBER_TOPIC_PREFIX = "subscriber:"

fun topicOf(subscriberId: String): String = "$SUBSCRIBER_TOPIC_PREFIX$subscriberId"

// AUTH TIER: user token. A stream is per subscriber and carries their counters, so the topic name is
// taken from the VERIFIED TOKEN and never from a query parameter — a stream addressed by a parameter
// is every subscriber's screen for anybody who asks.
fun Route.realtimeRoutes() {
    val broadcaster by inject<KompotUpdateBroadcaster>()

    sse("/api/v1/realtime") {
        val topic = topicOf(call.subscriberId())

        // Unlimited rather than a fixed buffer: the broadcaster offers into this channel and drops on
        // a full one, so a bound here would silently lose updates for a client that is merely slow.
        // The frames are small and a slow client disconnects long before the memory matters.
        val channel = Channel<String>(Channel.UNLIMITED)
        broadcaster.subscribe(topic, channel)

        try {
            for (payload in channel) {
                send(ServerSentEvent(data = payload))
            }
        } finally {
            // In a finally, because the ordinary end of this loop is the client going away — a closed
            // laptop, a lost network — and a subscriber set that only shrinks on a graceful close is
            // a set that only grows.
            broadcaster.unsubscribe(topic, channel)
        }
    }
}

// One place that turns a component into a frame, so the wire form cannot differ between the two
// callers that push one.
class ComponentBroadcaster(
    private val broadcaster: KompotUpdateBroadcaster,
    private val json: Json,
) {
    suspend fun push(
        subscriberId: String,
        componentId: String,
        component: KompotComponent,
    ) {
        broadcaster.broadcast(
            topicOf(subscriberId),
            json.encodeToString(UpdateComponentMessage.serializer(), UpdateComponentMessage(componentId, component)),
        )
    }
}
