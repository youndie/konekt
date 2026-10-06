package io.konekt.realtime

import io.github.youndie.kompot.realtime.redis.RedisKompotUpdateBus
import io.github.youndie.kompot.realtime.server.InMemoryKompotUpdateBus
import io.github.youndie.kompot.realtime.server.KompotBusMessage
import io.github.youndie.kompot.realtime.server.KompotUpdateBus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// THE REALTIME BUS SHARED BY EVERY REPLICA (`B-136`), over kompot's `kompot-realtime-redis` and the
// portfolio's RESP store, kesh. One replica publishes, all of them hear it, and each hands the update
// to the SSE connections it holds — so a client attached to one pod sees what another produced.
//
// WHAT THIS ADDS TO KOMPOT'S BUS is that it does not take the server down with it, which the bare one
// does in two ways:
//
// - the subscription is a flow that FAILS when the store is unreachable — at startup, or when it goes
//   away — and the broadcaster collects it once, so a store that was late by a second meant a server
//   that never delivered another update. Here a failed subscription is said out loud and tried again;
// - a publish while the connection is down is QUEUED by the client and its future does not complete,
//   so the caller waits for as long as the store is gone. The caller here is the usage consumer, right
//   after its commit, and a consumer stuck on a push is one that applies nothing. Here a publish is
//   given [publishTimeout] and then dropped, with a line in the log.
//
// Dropping is kompot's own stance, not a shortcut: an update is losable by design, because the next
// fetch of the screen carries the current state. What must not be lost travels through the outbox.
class SharedUpdateBus(
    private val delegate: KompotUpdateBus,
    private val describe: String,
    private val publishTimeout: Duration = 2.seconds,
    private val retryAfter: Duration = 1.seconds,
) : KompotUpdateBus {
    private val logger = LoggerFactory.getLogger("io.konekt.realtime.bus")

    override suspend fun publish(
        topic: String,
        payload: String,
    ) {
        val sent =
            try {
                withTimeoutOrNull(publishTimeout) { delegate.publish(topic, payload) } != null
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                logger.warn("a live update for {} was not published to {}: {}", topic, describe, failure.message)
                return
            }
        if (!sent) {
            logger.warn(
                "a live update for {} was dropped: {} did not answer within {}",
                topic,
                describe,
                publishTimeout,
            )
        }
    }

    override fun messages(): Flow<KompotBusMessage> =
        delegate.messages().retryWhen { failure, attempt ->
            if (failure is CancellationException) return@retryWhen false
            // The first one at WARN, so a store that is down is visible; the rest at INFO, so a store
            // that stays down for an hour is not a thousand warnings.
            if (attempt == 0L) {
                logger.warn(
                    "the realtime bus at {} is unreachable; live updates wait for it: {}",
                    describe,
                    failure.message,
                )
            } else {
                logger.info("the realtime bus at {} is still unreachable (attempt {})", describe, attempt + 1)
            }
            delay(retryAfter)
            true
        }

    companion object {
        // The in-memory bus for no URL — right for one replica — and the shared one otherwise. The
        // URL's credentials, if it has any, are not what gets logged.
        fun of(url: String?): KompotUpdateBus {
            val logger = LoggerFactory.getLogger("io.konekt.realtime.bus")
            if (url.isNullOrBlank()) {
                logger.info("live updates: the in-memory bus — one replica only")
                return InMemoryKompotUpdateBus()
            }
            val where = url.substringAfter('@')
            logger.info("live updates: the shared bus at {}", where)
            return SharedUpdateBus(RedisKompotUpdateBus.create(url), describe = where)
        }
    }
}
