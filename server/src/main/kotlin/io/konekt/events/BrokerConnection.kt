package io.konekt.events

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import ru.workinprogress.booblik.net.client.BooblikConnection
import ru.workinprogress.booblik.net.client.Producer
import java.io.Closeable
import java.net.InetSocketAddress

// One connection and one producer for the process, held so they can be closed.
//
// The producer is an accumulator with a coroutine of its own: it collects records for a few
// milliseconds before sending, which booblik's own measurement puts at 54x against sending them one
// at a time. So it is not a per-call object — creating one per event would give up the single
// largest factor in the broker and leave a coroutine behind on every publish.
class BrokerConnection(
    host: String,
    port: Int,
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Exposed because a CONSUMER needs it: `Producer` wraps the connection for publishing, and
    // reading a topic goes through the connection itself. Private until the traffic chain was
    // actually started, which is how a hole like that stays unnoticed — nothing needed it.
    val connection = BooblikConnection(InetSocketAddress(host, port), scope)

    val producer: Producer = Producer(connection, scope)

    override fun close() {
        producer.close()
        connection.close()
        scope.cancel()
    }
}
