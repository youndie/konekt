package io.konekt.mocks.traffic

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
import ru.workinprogress.booblik.TopicName

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

        // FROM WHERE THE BROKER IS NOW, and this is the decision the split forces into the open.
        //
        // For a SIMULATED feed it is the only sensible answer: replaying a day of invented usage on
        // every restart would empty every counter in the product. For a REAL one the correct default
        // is the opposite — a deployment that restarts should apply what arrived while it was down.
        //
        // It is the end for both, and the reason is not preference. booblik keeps **no consumer
        // offsets** — that absence is what removes the group coordinator and the cluster consensus
        // behind it — so "where we left off" is not something the broker can be asked. It would have
        // to be a position this application stored itself, in its own table, updated per batch, with
        // all the redelivery questions that opens.
        //
        // So the limitation is stated rather than hidden: **usage published while this process is
        // down is not applied when it comes back.** For a reference build on a fictional feed that is
        // an acceptable cost and it is written into `konekt-broker.md`; for a real integration it is
        // the first thing that would have to change.
        //
        // AND FOR TWO SEASONS THIS LINE DID NOT GIVE THE END (`B-108`). It used to build a
        // consumer at offset ZERO, poll once and take the position: one `maxBytes` of records in
        // from the START of the log, which is a different number entirely. Measured on the stage
        // deployment: it began at 11915 while the log ended at 374473, so every restart replayed
        // 362,558 historical usage events against live counters — the exact thing the paragraph
        // above says must not happen, written correctly and implemented backwards.
        //
        // It was also about to stop working at all. `B-100` turned retention on, so the log's start
        // will move above zero, and a fetch below the start is `OFFSET_OUT_OF_RANGE` — a throw out
        // of `start()` on the first boot after the first segment is deleted.
        //
        // METADATA answers both, cannot read a record, and cannot be out of range.
        val from = info.highWatermark

        logger.info("usage consumer starting on partition {} from offset {}", partition, from)

        return UsageConsumer(connection, consume, push, cards, roaming, roamingCards, clock, json)
            .start(scope, partition, from)
    }
}
