package io.konekt.mocks.traffic

import io.github.youndie.booblik.Offset
import io.github.youndie.booblik.TopicName
import io.konekt.db.ConsumerPositions
import io.konekt.events.BrokerConnection
import io.konekt.events.EventTopics
import io.konekt.feature.roaming.server.domain.RoamingPackages
import io.konekt.feature.usage.server.data.UsageCounterCards
import io.konekt.feature.usage.server.domain.ConsumeUsageUseCase
import io.konekt.realtime.ComponentBroadcaster
import io.konekt.roaming.RoamingPackageCards
import io.konekt.time.KonektClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

// THE PRODUCT'S OWN WORKER: whatever arrives on the `usage` topic is applied to the counters and
// pushed to whoever is looking at a screen.
//
// IT USED TO BE WELDED TO THE SIMULATOR. `TrafficChain.start()` built both ends and started them
// together, and nothing else constructed a `UsageConsumer` — so with `SIMULATE_TRAFFIC` off, which is
// the default and what the chart requires above one replica, **no process in this build read the
// topic at all**. The broker accepted events and nobody applied them.
//
// The welding was right when it was written: `B-16` exists because both halves were built, tested and
// constructed by nothing, and putting them in one starter is what fixed that. What changed is the
// claim. The chain broker → consumer → counter → realtime → screen is described as *the same chain a
// real integration would use*, and a real integration could not use it: switching the consumer on
// switched on a fake producer that drains every subscriber's allowance beside it (`B-89`).
class UsageChain(
    private val connection: BrokerConnection,
    private val consume: ConsumeUsageUseCase,
    private val push: ComponentBroadcaster,
    private val cards: UsageCounterCards,
    private val roaming: RoamingPackages,
    private val roamingCards: RoamingPackageCards,
    private val clock: KonektClock,
    private val json: Json,
    private val positions: ConsumerPositions,
) {
    private val logger = LoggerFactory.getLogger("io.konekt.usage.chain")

    suspend fun start(scope: CoroutineScope): Job {
        // ONE METADATA CALL FOR BOTH ANSWERS: which partition, and where its log ends. `B-108` is
        // why this is metadata and not a poll — see below.
        val info =
            connection.connection
                .metadata(listOf(TopicName(EventTopics.USAGE)))
                .topics
                .singleOrNull()
                ?.partitions
                ?.firstOrNull()
                ?: error("the broker has no partition for ${EventTopics.USAGE}")
        val partition = info.partition

        // WHERE THIS CONSUMER GOT TO, from its own table (`B-134`), checked against the log.
        //
        // This used to be the end of the log on every start, with the reason written here: booblik
        // keeps no consumer offsets, so "where we left off" would have to be "a position this
        // application stored itself, in its own table, updated per batch". That is what it is now —
        // and updated in the same transaction as the decrements, so it can neither run ahead of them
        // nor fall behind.
        //
        // THE END OF THE LOG IS STILL THE FIRST POSITION, recorded as such: the first start of a
        // deployment applies what arrives from now on, not a log's worth of history (`B-108`
        // measured what replaying it does to live counters). Every start after that carries on.
        val key = UsageConsumer.keyOf(partition)
        val stored = positions.load(key)
        val from =
            when {
                stored == null -> {
                    val first = positions.seed(key, info.highWatermark.value)
                    logger.info("usage consumer has no stored position; starting at the end of the log, {}", first)
                    Offset(first)
                }

                // A LOG YOUNGER THAN THE POSITION: the broker lost its volume, or a tail it had
                // acknowledged as written, or the topic was recreated. Carrying on at the end would
                // hide that loss; carrying on at the stored number would read whatever lies there
                // now. Neither is a decision this process can make, so it does not start.
                stored > info.highWatermark.value -> {
                    val refusal =
                        "the stored usage position $stored is past the end of the log, " +
                            "${info.highWatermark.value}: the broker's log is younger than this consumer. " +
                            "Not starting — move or delete the row in consumer_position once the loss is understood"
                    logger.error(refusal)
                    error(refusal)
                }

                // RETENTION PASSED THE CONSUMER while it was down: a loss of known size, said out loud
                // and stored, then the consumer carries on from the start of what is left.
                stored < info.logStartOffset.value -> {
                    if (positions.advance(key, stored, info.logStartOffset.value)) {
                        logger.warn(
                            "retention deleted usage records {}..{} before this consumer applied them: " +
                                "{} usage events were never applied. Resuming at {}",
                            stored,
                            info.logStartOffset.value - 1,
                            info.logStartOffset.value - stored,
                            info.logStartOffset.value,
                        )
                    }
                    Offset(positions.load(key) ?: info.logStartOffset.value)
                }

                else -> {
                    Offset(stored)
                }
            }

        logger.info("usage consumer starting on partition {} from offset {}", partition, from)

        return UsageConsumer(connection, consume, push, cards, roaming, roamingCards, clock, json, positions)
            .start(scope, partition, from)
    }
}
