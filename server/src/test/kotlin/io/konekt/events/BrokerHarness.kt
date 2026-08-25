package io.konekt.events

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    // Exactly what deploy/compose.yaml declares. EventTopicsTest holds the two together, because
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
}
