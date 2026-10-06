package io.konekt.realtime

import com.github.dockerjava.api.model.ExposedPort
import com.github.dockerjava.api.model.HostConfig
import com.github.dockerjava.api.model.PortBinding
import com.github.dockerjava.api.model.Ports
import io.github.youndie.kompot.realtime.redis.RedisKompotUpdateBus
import io.github.youndie.kompot.realtime.server.KompotUpdateBroadcaster
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

// `B-136`: the live-update bus shared by every replica, against kesh from its published image — the
// store the stand and the chart run, not a Redis that stands in for it.
class SharedUpdateBusTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val containers = mutableListOf<GenericContainer<*>>()

    @AfterTest
    fun stop() {
        scope.cancel()
        containers.forEach { it.stop() }
    }

    // kesh, optionally on a fixed host port — the second test needs the store to appear on an address
    // the bus was already pointed at.
    private fun kesh(hostPort: Int? = null): GenericContainer<*> =
        GenericContainer(DockerImageName.parse(IMAGE))
            .withEnv("KESH_MAXMEMORY", "67108864")
            .withExposedPorts(RESP, HTTP)
            .apply {
                if (hostPort != null) {
                    withCreateContainerCmdModifier { command ->
                        val hostConfig = command.hostConfig ?: HostConfig.newHostConfig()
                        command.withHostConfig(
                            hostConfig.withPortBindings(
                                // Both, because this REPLACES the bindings: the HTTP port left out
                                // here is a readiness probe with no address.
                                PortBinding(Ports.Binding.bindPort(hostPort), ExposedPort(RESP)),
                                PortBinding(Ports.Binding.empty(), ExposedPort(HTTP)),
                            ),
                        )
                    }
                }
            }.waitingFor(Wait.forHttp("/health/ready").forPort(HTTP))
            .also { containers += it }
            .apply { start() }

    private fun GenericContainer<*>.url(): String = "redis://$host:${getMappedPort(RESP)}"

    // A broadcaster as a replica has one: the shared bus, started on the replica's worker scope.
    private fun replica(url: String): KompotUpdateBroadcaster =
        KompotUpdateBroadcaster(SharedUpdateBus(RedisKompotUpdateBus.create(url), url)).also { it.start(scope) }

    private suspend fun Channel<String>.next(): String? = withTimeoutOrNull(5.seconds) { receive() }

    @Test
    fun `an update broadcast on one replica reaches a subscriber attached to another`() =
        runBlocking {
            val store = kesh()
            val a = replica(store.url())
            val b = replica(store.url())
            val onB = Channel<String>(Channel.UNLIMITED)
            b.subscribe("home:subscriber-1", onB)
            // The pattern subscription is asynchronous; an update before it is in place is lost by
            // design, so the first broadcast is repeated until one lands rather than slept for.
            val received =
                withTimeoutOrNull(15.seconds) {
                    var got: String? = null
                    while (got == null) {
                        a.broadcast("home:subscriber-1", "counter-data")
                        got = withTimeoutOrNull(500) { onB.receive() }
                    }
                    got
                }
            assertEquals("counter-data", received, "replica B never heard what replica A broadcast")

            // And only the subject's subscribers hear it: another topic on B stays silent.
            val other = Channel<String>(Channel.UNLIMITED)
            b.subscribe("home:subscriber-2", other)
            a.broadcast("home:subscriber-1", "again")
            assertEquals("again", onB.next())
            assertEquals(
                null,
                withTimeoutOrNull(1.seconds) { other.receive() },
                "an update reached the wrong subscriber",
            )
        }

    // KESH DOWN IS NOT THE SERVER DOWN. The bare bus fails its subscription once and is never collected
    // again, and its publish waits on a queued command for as long as the store is gone — the usage
    // consumer pushes right after its commit, so that wait would stop it applying anything.
    @Test
    fun `with kesh unreachable a replica keeps going, and live updates resume when kesh returns`() =
        runBlocking {
            val port = ServerSocket(0).use { it.localPort }
            val url = "redis://localhost:$port"
            val a = replica(url)
            val b = replica(url)
            val onB = Channel<String>(Channel.UNLIMITED)
            b.subscribe("home:subscriber-1", onB)

            val started = TimeSource.Monotonic.markNow()
            a.broadcast("home:subscriber-1", "lost")
            assertTrue(started.elapsedNow() < 5.seconds, "a publish with kesh down took ${started.elapsedNow()}")

            kesh(hostPort = port)

            val received =
                withTimeoutOrNull(60.seconds) {
                    var got: String? = null
                    while (got == null) {
                        a.broadcast("home:subscriber-1", "back")
                        got = withTimeoutOrNull(500) { onB.receive() }
                        if (got == null) delay(500)
                    }
                    got
                }
            assertNotNull(received, "live updates did not resume after kesh came up")
            assertEquals("back", received)
        }

    private companion object {
        // Pinned to a commit, like every image this build runs: `main` moves (youndie/kesh B-32).
        const val IMAGE = "ghcr.io/youndie/kesh:sha-4ad497912a3e5e2f7b91273d18b657d0885d3f03"
        const val RESP = 6379
        const val HTTP = 8080
    }
}
