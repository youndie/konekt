package io.konekt.health

import io.github.youndie.kore.health.HealthCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// WHAT THIS SERVER NEEDS IN ORDER TO BE READY, as opposed to alive.
//
// The distinction is the whole of konekt#32. A liveness probe that reads the store restarts a pod for
// an outage a restart cannot fix; a readiness probe that reads nothing lets a pod with an unreachable
// database keep taking traffic. Both were the same route here, answering `"ok"` for as long as the
// process existed.
//
// These are asked by kore's `HealthRegistry` on a loop of its own, and the probe serves the remembered
// answer. So a check may block on its dependency without the probe ever doing so — which is what keeps
// `timeoutSeconds` in the chart from deciding the verdict.
//
// ONE CHECK, AND THE BROKER IS DELIBERATELY NOT ONE. A broker that is away does not stop this server
// serving screens: the outbox holds the events and the relay retries, which is what an outbox is for.
// What it stops is live updates — and a readiness check for it would take the pod out of rotation for
// a condition the pod can serve through. `B-107` made a broker reconnect the ordinary case rather than
// an incident, so flapping readiness on it would be worse than the outage.
//
// Written down here rather than left as an absence: a reader asking "why is the broker not checked"
// should find the answer where the checks are, not conclude it was forgotten.

// THE DATABASE, and this is the one dependency without which nothing this server does works: every
// screen reads it, and a pod that cannot reach it has nothing to serve.
class DatabaseHealthCheck(
    private val database: Database,
    override val timeout: Duration = 2.seconds,
) : HealthCheck {
    override val name: String = "postgres"

    override suspend fun check() {
        // The cheapest question that still crosses the pool AND the socket. `isValid` on a connection
        // would ask the driver rather than the server, and a pool handing out a connection to a
        // database that has gone away answers that happily.
        withContext(Dispatchers.IO) {
            transaction(database) { exec("SELECT 1") }
        }
    }
}
