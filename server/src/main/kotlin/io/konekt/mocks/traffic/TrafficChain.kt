package io.konekt.mocks.traffic

import io.konekt.events.BrokerConnection
import io.konekt.events.EventTopics
import io.konekt.feature.roaming.server.domain.RoamingPackages
import io.konekt.feature.usage.server.data.UsageCounterCards
import io.konekt.feature.usage.server.domain.ConsumeUsageUseCase
import io.konekt.feature.usage.server.domain.UsageCounters
import io.konekt.realtime.ComponentBroadcaster
import io.konekt.roaming.RoamingPackageCards
import io.konekt.time.KonektClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import ru.workinprogress.booblik.TopicName
import ru.workinprogress.booblik.net.client.Consumer
import kotlin.time.Duration

// Both ends of the simulated traffic, started together, because separately they are two halves of a
// demonstration and neither is worth having alone.
//
// IT EXISTS BECAUSE NEITHER WAS EVER STARTED. `TrafficSimulator` and `UsageConsumer` were written,
// covered end to end against a real broker, and constructed by nothing outside that test — B-16
// closed on acceptance criteria that were all about the chain being *tested*. A chain that is tested
// and never started passes every one of them.
class TrafficChain(
    private val connection: BrokerConnection,
    private val counters: UsageCounters,
    private val consume: ConsumeUsageUseCase,
    private val push: ComponentBroadcaster,
    private val cards: UsageCounterCards,
    private val roaming: RoamingPackages,
    private val roamingCards: RoamingPackageCards,
    private val clock: KonektClock,
    private val json: Json,
    // How long a bought package lies dormant before the simulation starts it. Threaded from the
    // configuration rather than defaulted here, so the stand and a deployment can differ.
    private val dormantFor: Duration,
) {
    private val logger = LoggerFactory.getLogger("io.konekt.mocks.traffic.chain")

    suspend fun start(scope: CoroutineScope): List<Job> {
        val topic = connection.producer.topic(TopicName(EventTopics.USAGE))
        val partition = topic.partitions.first()

        // FROM WHERE THE BROKER IS NOW, not from zero. booblik stores no consumer offsets — that
        // absence is what removes the group coordinator and the cluster consensus behind it — so a
        // starting point has to be chosen here, and replaying a day of simulated usage on every
        // restart would empty every counter in the product.
        val from =
            Consumer(connection.connection, TopicName(EventTopics.USAGE), partition).let {
                it.poll()
                it.position
            }

        logger.info("DEV ONLY — traffic simulator starting on partition {} from offset {}", partition, from)

        val simulator =
            TrafficSimulator(
                producer = connection.producer,
                // Only subscribers who have something to spend. Publishing for anyone else produces
                // events the consumer correctly ignores, and a simulator producing only ignored
                // events looks exactly like one that is not running.
                subscribers = { counters.subscribersWithCounters() },
                // Trips already under way. Kept separate from the arrivals below, and the separation
                // is the feature: a tick must not start a dormant package, or the state this exists
                // to show — bought, not counting — is gone five seconds after the purchase.
                travelling = { roaming.travelling() },
                // AND THE ARRIVALS, which used to be a public development route (`B-88`). The
                // simulation flies a subscriber out once their package has been dormant long enough
                // to be looked at, so nothing outside this process decides it.
                awaitingArrival = { before -> roaming.awaitingArrival(before) },
                clock = clock,
                json = json,
                dormantFor = dormantFor,
            )

        val consumer = UsageConsumer(connection.connection, consume, push, cards, roaming, roamingCards, clock, json)

        return listOf(simulator.start(scope), consumer.start(scope, partition, from))
    }
}
