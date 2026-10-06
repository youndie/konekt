package io.konekt.leader

import io.github.youndie.vojak.Election
import io.github.youndie.vojak.ElectionTiming
import io.github.youndie.vojak.Leadership
import io.github.youndie.vojak.LockName
import io.github.youndie.vojak.Namespace
import io.github.youndie.vojak.Vojak
import io.github.youndie.vojak.jdbc.JdbcLockStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import org.slf4j.LoggerFactory
import javax.sql.DataSource

// THE REPLICA THAT RUNS WHAT MUST RUN ONCE (`B-135`).
//
// Three workers are wrong twice: the traffic simulator publishes fictional usage, so two pods spend an
// allowance twice as fast; petich's outbox relay has no claim, so two pods publish each row; and the
// usage consumer, correct on any number of replicas since `B-134`, does every fetch twice. They run
// inside vojak's `whileLeader` on the replica holding the election — an advisory lock on konekt's own
// Postgres, through the `DataSource` it already has (`vojak-jdbc`), so no coordinator and no second
// driver.
//
// What vojak promises is "at most one LEADER at any instant", not "at most one effect": a leader that
// stalls past its lease is replaced and may still finish a statement it had sent. The consumer is fenced
// by its compare-and-set on the stored position; a stray simulator tick or outbox delivery is the cost,
// and both were already at-least-once.
class Singletons(
    private val vojak: Vojak,
    private val candidate: String,
    private val timing: ElectionTiming = ElectionTiming(),
    private val name: LockName = NAME,
) {
    private val logger = LoggerFactory.getLogger("io.konekt.leader")

    // Campaigns on [scope] and runs [work] whenever this replica leads. [work] launches its workers
    // into the scope it is given; losing the leadership cancels that scope, and the workers with it,
    // before the election reports anything but `Leader` again.
    //
    // Named `elect` and not `start`: it starts nothing itself, and the workers it runs are started by
    // name in the composition root, where `WorkersAreStartedTest` looks for them.
    suspend fun elect(
        scope: CoroutineScope,
        work: suspend CoroutineScope.() -> Unit,
    ): Election {
        val election =
            vojak.elect(
                name,
                candidate,
                scope,
                timing,
                onStoreFailure = { logger.warn("the leader election could not reach Postgres", it) },
                onWorkFailure = { logger.error("a singleton failed on the leader; stepping down", it) },
            ) { epoch ->
                logger.info("{} leads {}, epoch {}", candidate, name.value, epoch)
                // A SUPERVISOR, so one worker failing does not take the others with it — or the
                // leadership: the usage consumer refusing to start (a position past the end of the
                // log) stops the consumer, as it did before `B-135`, and not the relay beside it.
                supervisorScope {
                    work()
                    awaitCancellation()
                }
            }
        // Every change after the first, which is always Follower and says nothing.
        scope.launch {
            election.leadership.drop(1).collect { state ->
                if (state !is Leadership.Leader) logger.info("{} is {} for {}", candidate, state, name.value)
            }
        }
        return election
    }

    companion object {
        // What every replica runs once, whatever its configuration: the outbox relay and the usage
        // consumer. One election for both — they share a fate, and two would let them land on two
        // replicas for no benefit.
        val NAME: LockName = LockName.of("singletons")

        // THE SIMULATOR HAS AN ELECTION OF ITS OWN, and the stand is what said so. It is a singleton
        // among the replicas that have it SWITCHED ON, not among all of them: the stand runs a second
        // server on the same database with the simulator off, and with one election that server won
        // it in CI and nothing published usage at all. Only a replica with the switch on campaigns here.
        val SIMULATOR: LockName = LockName.of("simulator")

        private val NOT_A_CANDIDATE_CHARACTER = Regex("[^A-Za-z0-9._-]")

        fun on(
            dataSource: DataSource,
            name: LockName = NAME,
            timing: ElectionTiming = ElectionTiming(),
        ): Singletons = Singletons(Vojak(JdbcLockStore(dataSource), Namespace.of("konekt")), candidate(), timing, name)

        // The pod's name — Kubernetes sets HOSTNAME to it — so `pg_stat_activity.application_name`
        // reads `vojak konekt-server-7f9c…` and an operator can tell which replica leads. vojak takes
        // `[A-Za-z0-9._-]{1,63}`; anything else is replaced rather than refused, because a hostname is
        // not ours to choose.
        fun candidate(hostname: String? = System.getenv("HOSTNAME")): String =
            hostname
                ?.replace(NOT_A_CANDIDATE_CHARACTER, "-")
                ?.take(63)
                ?.takeIf { it.isNotEmpty() }
                ?: "konekt-${ProcessHandle.current().pid()}"
    }
}
