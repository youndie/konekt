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
    private val roaming: RoamingPackages,
    private val clock: KonektClock,
    private val json: Json,
    // How long a bought package lies dormant before the simulation starts it. Threaded from the
    // configuration rather than defaulted here, so the stand and a deployment can differ.
    private val dormantFor: Duration,
) {
    private val logger = LoggerFactory.getLogger("io.konekt.mocks.traffic.chain")

    // THE SIMULATOR AND NOTHING ELSE. The consumer that used to start here is `UsageChain`, and the
    // split is `B-89`: this half is a MOCK and that half is the product's own worker, and a switch
    // that turned both on together meant a deployment could not read real usage without also
    // inventing some.
    suspend fun start(scope: CoroutineScope): Job {
        logger.info("DEV ONLY — traffic simulator starting, arrivals after {}", dormantFor)

        return TrafficSimulator(
            producer = connection.producer,
            // Only subscribers who have something to spend. Publishing for anyone else produces
            // events the consumer correctly ignores, and a simulator producing only ignored events
            // looks exactly like one that is not running.
            subscribers = { counters.subscribersWithCounters() },
            // Trips already under way. Kept separate from the arrivals below, and the separation is
            // the feature: a tick must not start a dormant package, or the state this exists to show
            // — bought, not counting — is gone five seconds after the purchase.
            travelling = { roaming.travelling() },
            // AND THE ARRIVALS, which used to be a public development route (`B-88`). The simulation
            // flies a subscriber out once their package has been dormant long enough to be looked at,
            // so nothing outside this process decides it.
            awaitingArrival = { before -> roaming.awaitingArrival(before) },
            clock = clock,
            json = json,
            dormantFor = dormantFor,
        ).start(scope)
    }
}
