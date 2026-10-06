package io.konekt.e2e

import io.github.youndie.booblik.TopicName
import io.github.youndie.booblik.net.client.BooblikConnection
import io.github.youndie.booblik.net.client.Producer
import io.github.youndie.kompot.realtime.UpdateComponentMessage
import io.konekt.components.UsageCounterCardComponent
import io.konekt.feature.realtime.shared.api.RealtimeStream
import io.ktor.client.HttpClient
import io.ktor.client.plugins.sse.serverSentEvents
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.sql.DriverManager
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

// `B-138`: the chart with two server replicas and kesh, in a kind cluster, driven through a leader
// kill and a rolling restart. Run by `scripts/rolling-check.sh two-replicas`, which installs the
// release and forwards the database and the broker; the server pods are forwarded here, because this
// is what kills and replaces them.
//
// The usage is published by the check itself, a megabyte an event, and the simulator is off: the
// verdict is an exact sum — every event applied once, none lost — and a simulator spending the same
// counter would make it a range. The simulator's own singleton is `SingletonsTest`'s and the stand's.
class TwoReplicasCheck {
    private val context = System.getProperty("konekt.replicas.context") ?: "kind-konekt-replicas"
    private val namespace = System.getProperty("konekt.replicas.namespace") ?: "konekt"
    private val kubectl = System.getProperty("konekt.replicas.kubectl") ?: "kubectl"
    private val broker = (System.getProperty("konekt.replicas.broker") ?: "127.0.0.1:19092").split(':')
    private val jdbcUrl = System.getProperty("konekt.stand.jdbc") ?: "jdbc:postgresql://127.0.0.1:15432/konekt"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val forwards = mutableListOf<Process>()
    private val started = TimeSource.Monotonic.markNow()
    private val log = CopyOnWriteArrayList<String>()

    private fun note(line: String) {
        val stamped = "${started.elapsedNow().inWholeMilliseconds} ms  $line"
        log += stamped
        println("two-replicas: $stamped")
    }

    @AfterTest
    fun stop() {
        scope.cancel()
        forwards.forEach { it.destroy() }
    }

    private fun kubectl(vararg args: String): String {
        val process =
            ProcessBuilder(listOf(kubectl, "--context", context, "-n", namespace) + args)
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(5, TimeUnit.MINUTES) && process.exitValue() == 0) {
            "kubectl ${args.joinToString(" ")}:\n$output"
        }
        return output.trim()
    }

    // Running and NOT being deleted: a pod draining after a rollout is still `Running` — and, until the
    // new ones win the election, still the leader — so the phase alone named it as a server it was
    // about to stop being.
    private fun serverPods(): List<String> =
        kubectl(
            "get",
            "pods",
            "-l",
            "app=konekt",
            "--field-selector=status.phase=Running",
            "-o",
            "go-template={{range .items}}{{if not .metadata.deletionTimestamp}}{{.metadata.name}}{{\"\\n\"}}{{end}}{{end}}",
        ).lines().filter { it.isNotBlank() }

    private fun awaitReady(count: Int) {
        kubectl("rollout", "status", "deployment/konekt", "--timeout=5m")
        val pods = serverPods()
        check(pods.size >= count) { "expected $count server pods, found $pods" }
    }

    // `kubectl port-forward` on a free local port, which it names on its first line.
    private fun forward(pod: String): String {
        val process =
            ProcessBuilder(kubectl, "--context", context, "-n", namespace, "port-forward", "pod/$pod", ":8080")
                .redirectErrorStream(true)
                .start()
        forwards += process
        val first = process.inputStream.bufferedReader().readLine() ?: error("port-forward to $pod printed nothing")
        val port =
            Regex("""127\.0\.0\.1:(\d+)""").find(first)?.groupValues?.get(1) ?: error("port-forward to $pod: $first")
        return "http://127.0.0.1:$port"
    }

    private fun remaining(subscriberId: String): Long =
        DriverManager.getConnection(jdbcUrl, "konekt", "konekt").use { connection ->
            connection
                .prepareStatement("select remaining_units from usage_counter where subscriber_id = ? and kind = 'data'")
                .use { statement ->
                    statement.setString(1, subscriberId)
                    statement.executeQuery().use { rows ->
                        check(rows.next()) { "no data counter for $subscriberId" }
                        rows.getLong(1)
                    }
                }
        }

    // Who holds a lease right now: a lease's session is named `vojak <pod>` once its lock is taken,
    // and only then — a replica that tries and fails never names its session.
    private fun leaders(): List<String> =
        DriverManager.getConnection(jdbcUrl, "konekt", "konekt").use { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery("select application_name from pg_stat_activity where application_name like 'vojak %'")
                    .use { rows -> buildList { while (rows.next()) add(rows.getString(1).removePrefix("vojak ")) } }
            }
        }

    // A stream on one pod, every frame it receives kept with the instant it arrived.
    private class Stream(
        val pod: String,
        val job: Job,
        val frames: CopyOnWriteArrayList<String>,
    )

    private fun stream(
        pod: String,
        url: String,
        token: String,
    ): Stream {
        val frames = CopyOnWriteArrayList<String>()
        val client: HttpClient = Stand.client(url)
        val job =
            scope.launch {
                try {
                    client.serverSentEvents(
                        urlString = RealtimeStream.PATH,
                        request = { header(HttpHeaders.Authorization, "Bearer $token") },
                    ) {
                        incoming.collect { event ->
                            val data = event.data ?: return@collect
                            val message = Stand.json.decodeFromString(UpdateComponentMessage.serializer(), data)
                            val card = message.component as? UsageCounterCardComponent ?: return@collect
                            frames += card.valueText
                        }
                    }
                } catch (cancellation: kotlinx.coroutines.CancellationException) {
                    throw cancellation
                } catch (ended: Exception) {
                    // A stream to a pod that was killed ends here; what it received is the verdict.
                    note("the stream on $pod ended: ${ended.message}")
                } finally {
                    client.close()
                }
            }
        return Stream(pod, job, frames)
    }

    private suspend fun awaitCount(
        expected: Long,
        subscriberId: String,
        within: Duration,
    ): Long? =
        withTimeoutOrNull(within) {
            while (remaining(subscriberId) != expected) delay(200.milliseconds)
            expected
        }

    @Test
    fun `two replicas apply every usage event once and reach every client, across a kill and a rolling restart`() =
        runBlocking {
            awaitReady(2)
            val pods = serverPods()
            note("server pods: $pods")
            val urls = pods.associateWith { forward(it) }

            // A subscriber with a home allowance, made through the first pod's API.
            val session =
                Stand.client(urls.getValue(pods.first())).use { client ->
                    val session = Stand.signIn(client)
                    Stand.topUp(client, session, majorUnits = 50)
                    Stand.buyAndConfirm(client, session, "home-20gb-30d")
                    session
                }
            val initial = assertNotNull(awaitRemaining(session.subscriberId), "the plan granted no data counter")
            note("subscriber ${session.subscriberId}, $initial MB")

            // ONE LEADER AT EVERY SAMPLE, from now to the end.
            val widest = AtomicInteger()
            val samples = AtomicInteger()
            val sampler =
                scope.launch {
                    while (isActive) {
                        val now = leaders()
                        widest.accumulateAndGet(now.size, ::maxOf)
                        samples.incrementAndGet()
                        if (now.size > 1) note("TWO LEADERS: $now")
                        delay(200.milliseconds)
                    }
                }

            val connection = BooblikConnection(InetSocketAddress(broker[0], broker[1].toInt()), scope)
            val producer = Producer(connection, scope)
            val usage = producer.topic(TopicName("usage"))
            var published = 0L

            suspend fun publish(
                count: Int,
                every: Duration,
            ) {
                repeat(count) {
                    usage
                        .send(
                            """{"subscriberId":"${session.subscriberId}","kind":"data","units":1}""".toByteArray(),
                        ).await()
                    producer.flush()
                    published++
                    delay(every)
                }
            }

            // PHASE 1: both pods up. The leader applies; the client on the OTHER pod must hear it
            // through kesh — the reason B-136 exists.
            val first = pods.map { stream(it, urls.getValue(it), session.accessToken) }
            delay(2.seconds)
            publish(10, 300.milliseconds)
            assertNotNull(
                awaitCount(initial - published, session.subscriberId, 60.seconds),
                "phase 1 not applied: ${remaining(session.subscriberId)}",
            )
            delay(2.seconds)
            first.forEach {
                assertTrue(
                    it.frames.isNotEmpty(),
                    "the client on ${it.pod} heard nothing while both pods were up",
                )
            }
            note("phase 1: ${first.map { "${it.pod}=${it.frames.size} frames" }}")

            // PHASE 2: the leader is killed — no drain, no clean release — while usage keeps coming.
            val leader = leaders().singleOrNull() ?: error("no single leader before the kill: ${leaders()}")
            note("killing the leader $leader")
            kubectl("delete", "pod", leader, "--grace-period=0", "--force")
            publish(10, 500.milliseconds)
            assertNotNull(
                awaitCount(initial - published, session.subscriberId, 90.seconds),
                "after the kill the counter is ${remaining(session.subscriberId)}, expected ${initial - published}",
            )
            note("phase 2: applied through the kill, leaders now ${leaders()}")
            awaitReady(2)

            // PHASE 3: a rolling restart under traffic.
            note("rolling restart")
            kubectl("rollout", "restart", "deployment/konekt")
            publish(20, 1.seconds)
            awaitReady(2)
            assertNotNull(
                awaitCount(initial - published, session.subscriberId, 90.seconds),
                "after the rollout the counter is ${remaining(session.subscriberId)}, expected ${initial - published}",
            )
            note("phase 3: applied through the rollout, leaders now ${leaders()}")

            // PHASE 4: a client on each of the NEW pods, and usage after they connected. Both must hear
            // the last update, whichever pod applied it.
            val now = serverPods()
            val last = now.map { stream(it, forward(it), session.accessToken) }
            delay(2.seconds)
            publish(5, 300.milliseconds)
            assertNotNull(awaitCount(initial - published, session.subscriberId, 60.seconds))
            delay(3.seconds)
            val heard = last.map { it.frames.lastOrNull() }
            note("phase 4: ${last.map { "${it.pod}=${it.frames.size} frames, last ${it.frames.lastOrNull()}" }}")
            heard.forEach { assertNotNull(it, "a client on a new pod heard nothing") }
            assertEquals(1, heard.toSet().size, "the clients on the two pods ended on different cards: $heard")

            // AND NOTHING LATE: a second application of any event would land after the count was met.
            delay(5.seconds)
            sampler.cancel()
            assertEquals(
                initial - published,
                remaining(session.subscriberId),
                "the counter moved after every event was applied once",
            )
            assertTrue(samples.get() > 50, "the leader was sampled ${samples.get()} times")
            assertTrue(widest.get() <= 1, "two leaders at once:\n${log.joinToString("\n")}")
            note(
                "verdict: $published events, ${initial -
                    remaining(
                        session.subscriberId,
                    )
                } MB applied, at most ${widest.get()} leader in ${samples.get()} samples",
            )
            connection.close()
        }

    private suspend fun awaitRemaining(subscriberId: String): Long? =
        withTimeoutOrNull(30.seconds) {
            while (true) {
                // Not yet granted is an empty result, which `remaining` reports by throwing.
                val value =
                    try {
                        remaining(subscriberId)
                    } catch (notYet: IllegalStateException) {
                        null
                    }
                if (value != null) return@withTimeoutOrNull value
                delay(200.milliseconds)
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }
}
