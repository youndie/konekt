package io.konekt.events

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import ru.workinprogress.booblik.net.client.BooblikConnection
import java.net.InetSocketAddress

// A real broker, from the published image, with the topics the deployment declares.
//
// Not a fake. booblik fixes its topic set at startup and creates nothing on demand, so the one thing
// worth testing here — that the three topics named in the compose file actually exist and can be
// written to and read from — is a property of the image and its configuration rather than of any
// code of ours. A fake would agree with whatever we told it.
object BrokerHarness {
    // Pinned, and the same tag the compose file runs. A stand that drifts to `latest` answers a
    // different question every few weeks.
    private const val IMAGE = "ghcr.io/youndie/booblik:0.3.0"

    const val PORT = 9092

    // Exactly what deploy/compose.yaml declares. `BrokerTopicsTest` holds the two together, because
    // routing an event to a topic the broker does not have is a publish that fails forever rather
    // than a topic that appears.
    const val TOPICS = "orders:1,usage:1,notifications:1"

    private val container: GenericContainer<*> =
        GenericContainer(DockerImageName.parse(IMAGE))
            .withEnv("BOOBLIK_TOPICS", TOPICS)
            .withExposedPorts(PORT)
            .waitingFor(Wait.forListeningPort())
            .apply { start() }

    val host: String get() = container.host
    val port: Int get() = container.getMappedPort(PORT)

    // A scope per connection rather than a shared one: a cancelled scope kills every connection on
    // it, and a test that closes its own must not take the next test's with it.
    fun connect(scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)): BooblikConnection =
        BooblikConnection(InetSocketAddress(host, port), scope)

    // THE HOLDER, for the callers that take one since `B-107`. It owns a scope of its own and is
    // closed by the caller like any other connection here — a shared one would let a test that
    // reconnects replace the socket another test is holding.
    fun broker(): BrokerConnection = BrokerConnection(host, port)

    // A BROKER OF ITS OWN, for a test that has to make the log BIG.
    //
    // The container above is shared by every test in this JVM, which is right for almost all of
    // them and wrong for one: `B-108`'s test has to put more than a megabyte on the `usage` topic
    // before it starts anything, and on a shared log that is not padding, it is pollution — it
    // broke `BrokerTopicsTest`, which reads its own probe back and got a filler record instead.
    //
    // Started per call and closed by the caller. A container start is a couple of seconds and this
    // is the only test that pays it.
    fun isolated(): Isolated {
        val container =
            GenericContainer(DockerImageName.parse(IMAGE))
                .withEnv("BOOBLIK_TOPICS", TOPICS)
                .withExposedPorts(PORT)
                .waitingFor(Wait.forListeningPort())
                .apply { start() }
        return Isolated(container)
    }

    class Isolated(
        private val container: GenericContainer<*>,
    ) : java.io.Closeable {
        val host: String get() = container.host
        val port: Int get() = container.getMappedPort(PORT)

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun connect(): BooblikConnection = BooblikConnection(InetSocketAddress(host, port), scope)

        fun broker(): BrokerConnection = BrokerConnection(host, port)

        override fun close() {
            scope.cancel()
            container.stop()
        }
    }
}
