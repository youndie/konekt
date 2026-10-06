package io.konekt.leader

import io.github.youndie.booblik.TopicName
import io.github.youndie.booblik.net.client.Consumer
import io.github.youndie.petich.outbox.OutboxRelayWorker
import io.github.youndie.petich.postgres.ExposedOutboxRepository
import io.github.youndie.petich.postgres.OutboxEventsTable
import io.github.youndie.vojak.Election
import io.github.youndie.vojak.ElectionTiming
import io.github.youndie.vojak.Leadership
import io.github.youndie.vojak.Namespace
import io.github.youndie.vojak.Vojak
import io.github.youndie.vojak.jdbc.JdbcLockStore
import io.konekt.events.BooblikOutboxPublisher
import io.konekt.events.BrokerHarness
import io.konekt.events.EventTopics
import io.konekt.testing.PostgresHarness
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

// `B-135`: the singletons run on one replica, chosen by vojak on konekt's own Postgres. Two "replicas"
// here are two `Singletons` on the same database — two elections, two sessions — which is what
// Postgres sees of two pods.
class SingletonsTest {
    // Short, so a test waits seconds rather than vojak's production lease of fifteen.
    private val timing =
        ElectionTiming(
            serverLease = 4.seconds,
            localLease = 2.seconds,
            renewEvery = 500.milliseconds,
            pollEvery = 200.milliseconds,
        )

    // A namespace per test instance, so no test's election is another's.
    private val namespace = Namespace.of("t" + Random.nextInt(0, Int.MAX_VALUE).toString(36))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val elections = mutableListOf<Election>()

    private fun replica(name: String) =
        Singletons(Vojak(JdbcLockStore(PostgresHarness.dataSource), namespace), "$name-${namespace.value}", timing)

    @BeforeTest
    fun clean() = PostgresHarness.truncateAll()

    @AfterTest
    fun stop() {
        runBlocking { withTimeoutOrNull(15.seconds) { elections.forEach { it.close() } } }
        scope.cancel()
    }

    private suspend fun awaitLeader(
        of: List<Election>,
        within: Duration,
    ): Election? {
        val started = TimeSource.Monotonic.markNow()
        while (started.elapsedNow() < within) {
            of.firstOrNull { it.leadership.value is Leadership.Leader }?.let { return it }
            delay(20.milliseconds)
        }
        return null
    }

    // A singleton that counts how many copies of itself are running, and the widest that count ever
    // was. Every sample is taken while it runs, so two leaders at once cannot hide between samples.
    private class Counting {
        val running = AtomicInteger()
        val widest = AtomicInteger()
        val starts = AtomicInteger()

        fun CoroutineScope.run() {
            launch {
                starts.incrementAndGet()
                widest.accumulateAndGet(running.incrementAndGet(), ::maxOf)
                try {
                    awaitCancellation()
                } finally {
                    running.decrementAndGet()
                }
            }
        }
    }

    @Test
    fun `of two replicas exactly one runs the singletons, and a clean stop hands them over within a poll`() =
        runBlocking {
            val worker = Counting()
            val a = replica("pod-a").elect(scope) { with(worker) { run() } }.also { elections += it }
            val b = replica("pod-b").elect(scope) { with(worker) { run() } }.also { elections += it }

            val leader = assertNotNull(awaitLeader(listOf(a, b), 10.seconds), "nobody led within 10 s")
            val other = if (leader === a) b else a
            // Several renewals' worth, so this is not one lucky instant.
            delay(2.seconds)
            assertEquals(1, worker.running.get(), "the singletons are not running on exactly one replica")

            val closed = TimeSource.Monotonic.markNow()
            leader.close()
            assertNotNull(awaitLeader(listOf(other), 3.seconds), "the other replica did not take over")
            // Inside a poll, not after `localLease`: the clean close marked the lease released.
            assertTrue(closed.elapsedNow() < timing.localLease, "the handover took ${closed.elapsedNow()}")
            delay(500.milliseconds)

            assertEquals(1, worker.widest.get(), "two copies of the singletons ran at once")
            assertEquals(2, worker.starts.get(), "the singletons did not move to the other replica")
            assertEquals(1, worker.running.get())
        }

    // A KILLED LEADER — its session ended by the server, as a node failure or a failover ends it. The
    // other replica must not start the singletons before the old leader can have noticed (vojak D12).
    @Test
    fun `when the leader's session is ended the singletons never run twice`() =
        runBlocking {
            val worker = Counting()
            val a = replica("pod-a").elect(scope) { with(worker) { run() } }.also { elections += it }
            val b = replica("pod-b").elect(scope) { with(worker) { run() } }.also { elections += it }
            val leader = assertNotNull(awaitLeader(listOf(a, b), 10.seconds))
            val name = if (leader === a) "pod-a-${namespace.value}" else "pod-b-${namespace.value}"

            transaction(PostgresHarness.database) {
                exec(
                    "select pg_terminate_backend(pid) from pg_stat_activity where application_name = 'vojak $name'",
                )
            }

            val watch = TimeSource.Monotonic.markNow()
            while (watch.elapsedNow() < timing.localLease + 3.seconds) {
                assertTrue(worker.running.get() <= 1, "two copies of the singletons ran at once")
                delay(5.milliseconds)
            }
            assertEquals(1, worker.widest.get())
            assertEquals(2, worker.starts.get(), "nobody took the singletons over after the kill")
        }

    // THE STAND'S SHAPE: two servers on one database, only one with the simulator switched on. With a
    // single election the other one won it in CI, and nothing published usage at all. The simulator
    // is a singleton among the replicas that HAVE it, so the one without must not hold it hostage.
    @Test
    fun `a replica with the simulator off leading the singletons does not stop the one with it on`() =
        runBlocking {
            val shared = Counting()
            val simulator = Counting()

            fun replica(
                name: String,
                election: io.github.youndie.vojak.LockName,
            ) = Singletons(
                Vojak(JdbcLockStore(PostgresHarness.dataSource), namespace),
                "$name-${namespace.value}",
                timing,
                election,
            )

            // The declining server first, so it is the one that leads the shared election.
            val declining = replica("declining", Singletons.NAME).elect(scope) { with(shared) { run() } }
            elections += declining
            assertNotNull(awaitLeader(listOf(declining), 10.seconds))

            elections += replica("server", Singletons.NAME).elect(scope) { with(shared) { run() } }
            val ownSimulator = replica("server", Singletons.SIMULATOR).elect(scope) { with(simulator) { run() } }
            elections += ownSimulator

            assertNotNull(awaitLeader(listOf(ownSimulator), 10.seconds), "the simulator never started anywhere")
            delay(1.seconds)
            assertEquals(1, simulator.running.get(), "the simulator is not running")
            assertEquals(1, shared.running.get(), "the shared singletons are not running exactly once")
        }

    // The relay with no claim, on two replicas: every row reaches the broker once.
    @Test
    fun `with two replicas each outbox row reaches the broker once`() =
        runBlocking {
            val table = OutboxEventsTable()
            val outbox = ExposedOutboxRepository(PostgresHarness.database, table)
            val connection = BrokerHarness.connect(scope)
            val before =
                connection
                    .metadata(listOf(TopicName(EventTopics.ORDERS)))
                    .topics
                    .single()
                    .partitions
                    .first()

            val run = namespace.value
            val ids = List(20) { "$run-$it:purchase.completed" }
            transaction(PostgresHarness.database) {
                ids.forEach { id ->
                    table.insert {
                        it[table.id] = id
                        it[table.type] = "purchase.completed"
                        it[payload] = Json.encodeToString(mapOf("orderId" to id, "planId" to "tr-10gb-30d"))
                        it[createdAt] = 0
                    }
                }
            }

            listOf("pod-a", "pod-b").forEach { pod ->
                val relay = OutboxRelayWorker(outbox, BooblikOutboxPublisher(BrokerHarness.broker()))
                elections += replica(pod).elect(scope) { relay.start(this) }
            }

            val pendingGone =
                withTimeoutOrNull(20.seconds) {
                    while (transaction(PostgresHarness.database) {
                            table.selectAll().where { table.status eq "PENDING" }.count()
                        } > 0
                    ) {
                        delay(100)
                    }
                    true
                }
            assertNotNull(pendingGone, "the outbox was not drained")
            delay(1.seconds)

            val consumer = Consumer(connection, TopicName(EventTopics.ORDERS), before.partition, before.highWatermark)
            val published = mutableListOf<String>()
            while (true) {
                val records = consumer.poll().records
                if (records.isEmpty()) break
                published += records.map { String(it) }.filter { run in it }
            }
            assertEquals(ids.size, published.size, "rows were published ${published.size} times for ${ids.size} rows")
            connection.close()
        }
}
