package io.konekt.mocks

import io.github.youndie.booblik.TopicName
import io.github.youndie.booblik.net.client.Producer
import io.github.youndie.kompot.generated.generatedKonektSerializersModule
import io.github.youndie.kompot.kompotCoreSerializersModule
import io.github.youndie.kompot.realtime.server.KompotUpdateBroadcaster
import io.konekt.db.ConsumerPositions
import io.konekt.db.tables.SubscriberTable
import io.konekt.events.BrokerConnection
import io.konekt.events.BrokerHarness
import io.konekt.events.EventTopics
import io.konekt.feature.roaming.server.domain.InMemoryRoamingPackages
import io.konekt.feature.usage.server.data.ExposedUsageCounters
import io.konekt.feature.usage.server.data.StaticUsageAddOns
import io.konekt.feature.usage.server.data.UsageCounterCards
import io.konekt.feature.usage.server.domain.ConsumeUsageUseCase
import io.konekt.feature.usage.server.domain.UsageCounter
import io.konekt.mocks.traffic.UsageChain
import io.konekt.mocks.traffic.UsageConsumer
import io.konekt.realtime.ComponentBroadcaster
import io.konekt.roaming.RoamingPackageCards
import io.konekt.testing.PostgresHarness
import io.konekt.time.KonektClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.plus
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

// `B-134`: the usage consumer's position is stored with the decrements it covers, so a restart
// carries on where the last batch committed and two consumers of one partition apply each event once.
// Against the real broker and the real database, through the production `UsageChain`.
@OptIn(ExperimentalUuidApi::class)
class UsagePositionTest {
    private val clock = KonektClock { Instant.fromEpochMilliseconds(1_700_000_000_000) }
    private val json =
        Json {
            ignoreUnknownKeys = true
            classDiscriminator = "type"
            serializersModule = kompotCoreSerializersModule + generatedKonektSerializersModule
        }
    private val counters = ExposedUsageCounters(PostgresHarness.database, clock)
    private val positions = ConsumerPositions(PostgresHarness.database)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val push = ComponentBroadcaster(KompotUpdateBroadcaster().also { it.start(scope) }, json)
    private val roaming = InMemoryRoamingPackages { clock.now() }
    private val connections = mutableListOf<BrokerConnection>()
    private lateinit var subscriberId: String

    @BeforeTest
    fun seed() {
        PostgresHarness.truncateAll()
        val id = Uuid.random().toString()
        subscriberId = id
        transaction(PostgresHarness.database) {
            SubscriberTable.insert {
                it[SubscriberTable.id] = id
                it[msisdn] = "1555011${(1000..9999).random()}"
                it[createdAt] = 0
            }
        }
    }

    @AfterTest
    fun stop() {
        scope.cancel()
        connections.forEach { it.close() }
    }

    // The production wrapper, each with its own broker connection — what a second process has.
    private fun chain(): UsageChain =
        UsageChain(
            BrokerConnection(BrokerHarness.host, BrokerHarness.port).also { connections += it },
            ConsumeUsageUseCase(counters),
            push,
            UsageCounterCards(StaticUsageAddOns()),
            roaming,
            RoamingPackageCards(),
            clock,
            json,
            positions,
        )

    private val writer by lazy { BrokerHarness.connect(scope) }
    private val producer by lazy { Producer(writer, scope) }

    private suspend fun publish(vararg megabytes: Long) {
        val usage = producer.topic(TopicName(EventTopics.USAGE))
        megabytes.forEach { units ->
            usage.send("""{"subscriberId":"$subscriberId","kind":"data","units":$units}""".toByteArray()).await()
        }
        producer.flush()
    }

    private suspend fun remaining(): Long? =
        counters.of(subscriberId).firstOrNull { it.kind == UsageCounter.Kind.DATA }?.remainingUnits

    private suspend fun remainingBecomes(expected: Long): Long? =
        withTimeoutOrNull(20.seconds) {
            while (remaining() != expected) delay(100)
            expected
        }

    // WHAT A RESTART USED TO COST: every event published while the process was down. The consumer
    // started at the end of the log, so 200 and 300 below were never applied and the counter stayed
    // at 9 900.
    @Test
    fun `usage published while the consumer is down is applied when it comes back`() {
        runBlocking {
            counters.grant(subscriberId, UsageCounter.Kind.DATA, 10_000)

            val first: Job = chain().start(scope)
            publish(100)
            assertNotNull(remainingBecomes(9_900), "the first consumer never applied its event")
            first.cancelAndJoin()

            publish(200, 300)
            delay(500)
            assertEquals(9_900, remaining(), "something applied usage while no consumer was running")

            val second = chain().start(scope)
            assertNotNull(
                remainingBecomes(9_400),
                "the restarted consumer did not apply what was published while it was down: " +
                    "the counter is at ${remaining()}",
            )
            second.cancel()
        }
    }

    // WHAT A SECOND REPLICA USED TO COST: every event twice. Both consumers read every batch; the
    // compare-and-set on the stored position lets exactly one of them apply it.
    @Test
    fun `two consumers of one partition apply each event once`() {
        runBlocking {
            counters.grant(subscriberId, UsageCounter.Kind.DATA, 10_000)
            val a = chain().start(scope)
            val b = chain().start(scope)

            publish(*LongArray(10) { 10 })

            assertNotNull(remainingBecomes(9_900), "the ten events were not applied: ${remaining()}")
            // Long enough for a second application of every event to land, had there been one.
            delay(2_000)
            assertEquals(9_900, remaining(), "the two consumers applied some events twice")
            a.cancel()
            b.cancel()
        }
    }

    // A LOG YOUNGER THAN THE POSITION is a loss this process cannot size, so it does not start —
    // carrying on at the end would hide it, and at the stored number would read other records.
    @Test
    fun `a stored position past the end of the log stops the start`() {
        runBlocking {
            val end =
                writer
                    .metadata(listOf(TopicName(EventTopics.USAGE)))
                    .topics
                    .single()
                    .partitions
                    .first()
            val beyond = end.highWatermark.value + 1_000
            positions.seed(UsageConsumer.keyOf(end.partition), beyond)

            val refusal = assertFailsWith<IllegalStateException> { chain().start(scope) }
            val message = refusal.message.orEmpty()
            assertTrue(
                "$beyond" in message && "${end.highWatermark.value}" in message,
                "the refusal names neither number: $message",
            )
            assertEquals(beyond, positions.load(UsageConsumer.keyOf(end.partition)), "the refusal moved the position")
        }
    }
}
