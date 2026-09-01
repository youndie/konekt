package io.konekt.events

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ClosedSendChannelException
import org.slf4j.LoggerFactory
import ru.workinprogress.booblik.net.client.BooblikConnection
import ru.workinprogress.booblik.net.client.Producer
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress

// One connection and one producer for the process — and, since `B-107`, one that can be REPLACED.
//
// The producer is an accumulator with a coroutine of its own: it collects records for a few
// milliseconds before sending, which booblik's own measurement puts at 54x against sending them one
// at a time. So it is not a per-call object — creating one per event would give up the single
// largest factor in the broker and leave a coroutine behind on every publish.
//
// WHAT `B-107` COST. `BooblikConnection` opens ONE `SocketChannel` in its constructor and has no
// reconnect of its own — the position and the socket both live on the client, which is the same
// decision that removes the group coordinator. Held for the life of the process, that made a broker
// pod being replaced permanent: every poll answered `EOFException: broker closed the connection` at
// five a second for as long as anyone watched, the broker reported `conns 0` because nothing ever
// dialled it again, live usage was dead, and the outbox relay retried each row against the same dead
// socket. A `kubectl rollout restart` fixed it in one go, which is the shape of a defect that hides:
// the cure is so easy that the cause never gets looked for.
//
// A broker restart is not an incident. It is a chart change, a node drain, an image bump — routine
// things this deployment does to itself.
class BrokerConnection(
    private val host: String,
    private val port: Int,
) : Closeable {
    private val logger = LoggerFactory.getLogger("io.konekt.events.broker")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private class Live(
        val generation: Int,
        val connection: BooblikConnection,
        val producer: Producer,
    )

    @Volatile
    private var live: Live = open(0)

    // WHICH SOCKET A CALLER IS HOLDING, as a number rather than as the object.
    //
    // The consumer holds a `BooblikConnection` and the outbox publisher holds `TopicHandle`s that
    // wrap a `Producer`; neither can compare its own object against the other's. A generation is
    // the one token both can carry, and it is what makes [reconnect] idempotent under a race: two
    // callers finding the same dead socket ask to replace the same generation, and the second one
    // finds the work already done instead of throwing the first one's fresh connection away.
    val generation: Int get() = live.generation

    val connection: BooblikConnection get() = live.connection

    val producer: Producer get() = live.producer

    private fun open(generation: Int): Live {
        val connection = BooblikConnection(InetSocketAddress(host, port), scope)
        return Live(generation, connection, Producer(connection, scope))
    }

    // Replaces the socket a caller found broken, unless somebody already has.
    //
    // `seen` is the generation the caller was using. Returns the generation in force afterwards, so
    // a caller can take a fresh `connection` or `producer` and record what it now holds.
    //
    // THROWS WHEN THE BROKER IS DOWN, deliberately: `BooblikConnection` dials in its constructor, so
    // there is nothing to hand back. The generation is left where it was, which means the next
    // caller to find the socket dead asks again — a retry loop made of the callers' own poll
    // intervals rather than of a schedule invented here.
    fun reconnect(seen: Int): Int =
        synchronized(lock) {
            val current = live
            if (current.generation != seen) return current.generation

            // Closing is best-effort on purpose. The socket is already broken by hypothesis and a
            // failure to close it must not stop the connection that replaces it. Written out rather
            // than as `runCatching`, which `RunCatchingUsageTest` forbids in production sources for
            // a reason that applies here too: it swallows cancellation.
            closeQuietly(current)

            val next = open(current.generation + 1)
            live = next
            logger.warn("reconnected to the broker at {}:{} — generation {}", host, port, next.generation)
            next.generation
        }

    companion object {
        // Whether a failure means THIS CONNECTION IS FINISHED rather than THIS CALL FAILED.
        //
        // TWO TYPES AND NOT ONE, and the second was found by a test rather than by reasoning. The
        // broker going away raises `EOFException: broker closed the connection` from the reader —
        // an `IOException`, and the one seen in production when a pod was replaced. Closing the
        // connection from THIS side, which is the only way a test reaches the same state without a
        // container of its own, raises `ClosedSendChannelException` from the outbound channel
        // instead. They are the two ends of one fact, and both users of this class ask the question
        // here rather than each writing its own list.
        //
        // The first version of the consumer's recovery matched `IOException` alone. It would have
        // worked in production and been exercised by nothing.
        fun isFinished(failure: Throwable): Boolean = failure is IOException || failure is ClosedSendChannelException
    }

    override fun close() {
        synchronized(lock) { closeQuietly(live) }
        scope.cancel()
    }

    private fun closeQuietly(what: Live) {
        try {
            what.producer.close()
        } catch (ignored: Exception) {
            logger.debug("closing the producer of generation {} failed", what.generation, ignored)
        }
        try {
            what.connection.close()
        } catch (ignored: Exception) {
            logger.debug("closing the connection of generation {} failed", what.generation, ignored)
        }
    }
}
