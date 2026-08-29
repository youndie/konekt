package io.konekt.e2e

import io.github.youndie.kompot.auth.UpdateSessionAction
import io.github.youndie.kompot.auth.kompotAuthSerializersModule
import io.github.youndie.kompot.decodeKompotAction
import io.github.youndie.kompot.form.standard.formStandardSerializersModule
import io.github.youndie.kompot.generated.generatedFormsSerializersModule
import io.github.youndie.kompot.generated.generatedKonektSerializersModule
import io.github.youndie.kompot.generated.generatedStandardSerializersModule
import io.github.youndie.kompot.kompotCoreSerializersModule
import io.github.youndie.kompot.standard.kompotStandardSerializersModule
import io.konekt.domain.Currency
import io.konekt.domain.Money
import io.konekt.feature.auth.shared.api.AuthOtp
import io.konekt.feature.auth.shared.api.DevOtp
import io.konekt.feature.auth.shared.api.DevOtpResponse
import io.konekt.feature.auth.shared.api.RequestOtpRequest
import io.konekt.feature.auth.shared.api.VerifyOtpRequest
import io.konekt.feature.auth.shared.api.authActionsSerializersModule
import io.konekt.feature.esim.shared.api.esimActionsSerializersModule
import io.konekt.feature.purchase.shared.api.CreatePurchaseRequest
import io.konekt.feature.purchase.shared.api.CreateTopUpRequest
import io.konekt.feature.purchase.shared.api.PurchaseOrderResponse
import io.konekt.feature.purchase.shared.api.Purchases
import io.konekt.feature.purchase.shared.api.TopUpResponse
import io.konekt.feature.purchase.shared.api.TopUps
import io.konekt.feature.purchase.shared.api.purchaseActionsSerializersModule
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.resources.Resources
import io.ktor.client.plugins.resources.get
import io.ktor.client.plugins.resources.post
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.plus
import java.sql.DriverManager
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

// What the suite talks to, and what it says when the stand is the thing that is wrong.
//
// A stand fails in ways a unit test cannot: a container that did not come up, a broker that died
// halfway, an image built from a distribution nobody rebuilt. Every one of those surfaces here as a
// wait that expires, and a bare timeout is the least useful sentence a test can end with. So the
// waiting is in one place and it ASKS THE STAND WHAT HAPPENED before it gives up.
object Stand {
    val serverUrl: String = System.getProperty("konekt.stand.server") ?: "http://127.0.0.1:8080"
    val decliningUrl: String = System.getProperty("konekt.stand.declining") ?: "http://127.0.0.1:8081"
    private val jdbcUrl: String = System.getProperty("konekt.stand.jdbc") ?: "jdbc:postgresql://127.0.0.1:55432/konekt"
    private val composeFile: String? = System.getProperty("konekt.stand.compose")

    // The module set a CLIENT of this server assembles. Two lists that must agree and cannot share a
    // definition is a seam that drifts — and this suite is the one place where a drift between them
    // would be visible at all.
    //
    // "Visible" was too generous. A missing COMPONENT module decodes a screen to nothing, which is
    // loud; a missing ACTION module decodes a button's action to `UnknownAction`, which is silent and
    // reads as a screen that offers nothing. All three action modules were absent here for as long as
    // this file has existed, and no test noticed because none of them read an action.
    val json: Json =
        Json {
            ignoreUnknownKeys = true
            classDiscriminator = "type"
            serializersModule =
                kompotCoreSerializersModule +
                kompotStandardSerializersModule +
                generatedStandardSerializersModule +
                generatedKonektSerializersModule +
                kompotAuthSerializersModule +
                // THE FORM HALF, ON THIS SIDE TOO, and they are two modules for two reasons.
                // `generatedFormsSerializersModule` carries the form COMPONENTS — the inputs and the
                // read-only field, which travel in the tree — and `formStandardSerializersModule` the
                // FIELD definitions that travel in the schema. A client registering only the first
                // decodes the screen and fails on `$.schema.fields[0]`, which is exactly what this
                // suite did the first time it asked for a form.
                generatedFormsSerializersModule +
                formStandardSerializersModule +
                // THE ACTIONS, and all three were missing until an install scenario tried to read one.
                //
                // Nothing failed. kompot answers an unregistered action with `UnknownAction`, so a
                // test that pulls the action off a button and looks at it gets null and concludes the
                // screen has no control — which is indistinguishable from a server that drew none.
                // The suite's own walk stood on step one for eight iterations and reported that the
                // activation code was never drawn, while the server was serving it.
                //
                // The three the CLIENT registers, not the five the server does: `petich` and the dev
                // screens are the server talking to itself.
                authActionsSerializersModule +
                esimActionsSerializersModule +
                purchaseActionsSerializersModule
        }

    fun client(baseUrl: String = serverUrl): HttpClient =
        HttpClient(CIO) {
            install(ContentNegotiation) { json(json) }
            install(Resources)
            install(SSE)
            defaultRequest {
                url(baseUrl)
                contentType(ContentType.Application.Json)
            }
        }

    // A number nobody else in the run is using. The stand keeps its database between scenarios on
    // purpose — a suite that truncates is a suite that cannot show two subscribers — so isolation is
    // by identity rather than by cleaning.
    fun freshMsisdn(): String = "1555${(1_000_000..9_999_999).random()}"

    class Session(
        val msisdn: String,
        val subscriberId: String,
        val accessToken: String,
    )

    suspend fun signIn(client: HttpClient): Session {
        val msisdn = freshMsisdn()

        client.post(AuthOtp.Request(AuthOtp())) { setBody(RequestOtpRequest(msisdn)) }

        // The development endpoint that reads back what the SMSC would have carried. The boundary of
        // this system stops at the SMSC, so without it there is no way to sign in at all.
        val code = client.get(DevOtp(msisdn)).body<DevOtpResponse>().code

        val answer = client.post(AuthOtp.Verify(AuthOtp())) { setBody(VerifyOtpRequest(msisdn, code)) }
        val action = json.decodeKompotAction(answer.bodyAsText())

        check(action is UpdateSessionAction) { "verify answered ${action::class.simpleName}, not a session" }

        return Session(msisdn = msisdn, subscriberId = subscriberIdOf(msisdn), accessToken = action.accessToken)
    }

    // TOPPING UP THROUGH THE PRODUCT'S OWN PATH, which this used to do with an UPDATE straight at
    // the stand's database because nothing could add money at all (B-40).
    //
    // The difference is not tidiness. A scenario that seeds its precondition in SQL proves the
    // purchase works given a balance, and says nothing about how a balance is ever obtained — which
    // was the one thing this product could not do. Now every scenario's first step exercises the
    // top-up saga, so a break in it fails four tests rather than none.
    //
    // Whichever server the client points at, which is the whole switch: the payment mock refuses on
    // the declining one, and both servers share one database, so money put in through the approving
    // server is spendable through the refusing one.
    suspend fun topUp(
        client: HttpClient,
        session: Session,
        majorUnits: Long,
    ): TopUpResponse = topUpRaw(client, session, amountMinor = Money.ofMajor(majorUnits, Currency.DEFAULT).minorUnits)

    // THROUGH `Money.ofMajor` AND NOT `* 100`. The exponent belongs to the currency — which is the
    // whole reason `Money` exists rather than a `formatMoney(minor, currency)` helper — and a hundred
    // written out here is right for the dollar, wrong for the dinar, and a second place to change.
    // `B-67` is what a loose factor of a hundred costs when it is on the other side of a boundary.

    // Minor units, for the scenarios that are about the amount itself rather than about the money.
    suspend fun topUpRaw(
        client: HttpClient,
        session: Session,
        amountMinor: Long,
    ): TopUpResponse =
        client
            .post(TopUps()) {
                bearerAuth(session.accessToken)
                setBody(CreateTopUpRequest(amountMinor = amountMinor))
            }.body()

    // A COMPLETED PURCHASE, which several scenarios need as a precondition rather than as a subject.
    //
    // Through the product's own two steps — create, then confirm — for the same reason `topUp` runs
    // the saga instead of seeding SQL: a precondition arranged behind the application proves the rest
    // works given a state nothing can reach.
    suspend fun buyAndConfirm(
        client: HttpClient,
        session: Session,
        planId: String,
    ): PurchaseOrderResponse {
        val started =
            client
                .post(Purchases()) {
                    bearerAuth(session.accessToken)
                    setBody(CreatePurchaseRequest(planId))
                }.body<PurchaseOrderResponse>()

        return client
            .post(Purchases.ById.Confirm(Purchases.ById(orderId = started.orderId))) {
                bearerAuth(session.accessToken)
            }.body()
    }

    private fun subscriberIdOf(msisdn: String): String =
        connection().use { connection ->
            connection.prepareStatement("SELECT id FROM subscriber WHERE msisdn = ?").use { statement ->
                statement.setString(1, msisdn.trimStart('+'))
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "no subscriber for $msisdn — did verify create one?" }
                    rows.getString(1)
                }
            }
        }

    private fun connection() = DriverManager.getConnection(jdbcUrl, "konekt", "konekt")

    // The wait that names what went wrong.
    //
    // Every scenario here crosses several processes, so an expired wait means one of them is not
    // doing its job — and which one is a question the harness can answer and a person should not have
    // to. `docker compose ps` is the answer: the broker publishes no port and cannot be probed
    // directly, and it is the process whose death is quietest.
    suspend fun <T : Any> awaitOrExplain(
        what: String,
        timeout: Duration = 45.seconds,
        poll: Duration = 500.milliseconds,
        attempt: suspend () -> T?,
    ): T {
        val started = TimeSource.Monotonic.markNow()
        var lastFailure: Throwable? = null

        while (started.elapsedNow() < timeout) {
            try {
                attempt()?.let { return it }
            } catch (failure: Exception) {
                lastFailure = failure
            }
            kotlinx.coroutines.delay(poll)
        }

        throw AssertionError(
            buildString {
                append("waited $timeout for: $what")
                lastFailure?.let { append("\n  last failure: ${it::class.simpleName}: ${it.message}") }
                append("\n\n")
                append(standDiagnosis())
            },
        )
    }

    // What the stand looks like right now, in the failure message rather than in a log somebody has
    // to think to open.
    fun standDiagnosis(): String {
        val file = composeFile ?: return "  (no compose file configured, so the stand cannot be inspected)"

        val output =
            try {
                ProcessBuilder("docker", "compose", "-f", file, "ps", "--format", "{{.Service}} {{.State}} {{.Status}}")
                    .redirectErrorStream(true)
                    .start()
                    .let { process ->
                        val text = process.inputStream.bufferedReader().readText()
                        process.waitFor()
                        text
                    }
            } catch (unavailable: Exception) {
                return "  (docker could not be asked: ${unavailable.message})"
            }

        val lines = output.trim().lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return "  THE STAND IS NOT RUNNING — nothing is up. Start it: make stand-up"

        // HOW LOADED THE STAND IS, which was invisible and is the difference between two very
        // different failures wearing the same message.
        //
        // Several scenarios wait for the traffic simulator to move a counter belonging to a
        // subscriber they just made. The simulator publishes for EVERY subscriber holding one, every
        // five seconds, so what it produces grows with this number while the consumer that applies
        // the events does not. On a stand torn down that morning it is three; after a day of walking
        // through the application by hand it is dozens, and the same timeout means something else
        // entirely (`B-77`).
        //
        // A count and no verdict. What a large number MEANS is measured in that item and not decided
        // here — a harness that accused the stand of being busy would be as wrong as one that
        // accused the product of being slow.

        // A service is fine if it is running, or if it exited cleanly — the migration job is meant to
        // exit, and exiting with anything but 0 is exactly the case worth reporting. Parenthesised
        // because a mixed `&&`/`||` is where a precedence mistake hides, and this line decides what a
        // failure message accuses.
        val fine = { line: String -> line.contains(" running") || (line.contains(" exited") && line.contains("(0)")) }
        val down = lines.filterNot(fine)

        return buildString {
            appendLine("  the stand right now:")
            lines.forEach { appendLine("    $it") }
            simulatedSubscribers()?.let { appendLine("    the traffic simulator is publishing for $it subscribers") }
            counterTableBloat()?.let { appendLine("    $it") }
            if (down.isNotEmpty()) {
                appendLine()
                appendLine("  NOT RUNNING: ${down.joinToString("; ") { it.substringBefore(' ') }}")
                appendLine("  That, and not the timeout, is what this run actually hit.")
            }
            shadowedPorts().takeIf { it.isNotEmpty() }?.let { shadows ->
                appendLine()
                appendLine("  SOMETHING ELSE IS ON THESE PORTS:")
                shadows.forEach { appendLine("    $it") }
                appendLine("  The container is up and the test is not reaching it. Override the port")
                appendLine("  (METRIK_PORT / TRACY_PORT / KATCHER_PORT and the KONEKT_STAND_* URLs)")
                appendLine("  or stop whatever holds it.")
            }
        }
    }

    // WHAT THE SIMULATOR HAS DONE TO THE TABLE EVERY ONE OF THESE SCENARIOS READS.
    //
    // It UPDATEs three rows per subscriber every five seconds and never stops, so a stand left up
    // overnight accumulates dead row versions on `usage_counter` without bound. Whether that is what
    // makes these waits expire is `B-77`'s open question — and the question could not be answered the
    // first two times it reproduced, because by the time anyone thought to look the stand had been
    // torn down to check whether a fresh one was fine.
    //
    // So the number travels WITH the failure. That is the only way a reproduction that happens twice
    // a day and lives for ten seconds after it is noticed gets measured at all.
    private fun counterTableBloat(): String? =
        try {
            connection().use { connection ->
                connection
                    .prepareStatement(
                        "SELECT n_live_tup, n_dead_tup, pg_size_pretty(pg_total_relation_size(relid)) " +
                            "FROM pg_stat_user_tables WHERE relname = 'usage_counter'",
                    ).use { statement ->
                        statement.executeQuery().use { rows ->
                            if (rows.next()) {
                                "usage_counter: ${rows.getLong(1)} live rows, ${rows.getLong(2)} dead, " +
                                    "${rows.getString(3)} on disk"
                            } else {
                                null
                            }
                        }
                    }
            }
        } catch (unavailable: Exception) {
            null
        }

    // Null rather than a guess when the database cannot be asked: this line is context beside a
    // failure, and a failure message that invents a number is worse than one that omits it.
    private fun simulatedSubscribers(): Int? =
        try {
            connection().use { connection ->
                connection
                    .prepareStatement("SELECT count(DISTINCT subscriber_id) FROM usage_counter")
                    .use { statement ->
                        statement.executeQuery().use { rows -> if (rows.next()) rows.getInt(1) else null }
                    }
            }
        } catch (unavailable: Exception) {
            null
        }

    // A PORT THE STAND PUBLISHES AND SOMEBODY ELSE ANSWERS ON.
    //
    // This cost three red tests for six days, and the accusation was wrong in the most expensive way:
    // the suite said "waited 30s for katcher to show the crash" and katcher was working perfectly —
    // an unrelated local daemon held 127.0.0.1 on the same port, docker held the wildcard, and IPv4
    // went to the daemon. Every symptom pointed at the service that was innocent.
    //
    // `lsof` rather than a probe of the port's content: there is no request that distinguishes "this
    // is katcher" from "this is something that also serves JSON", and OWNERSHIP is the fact that
    // actually decides it. A machine without `lsof` gets nothing extra rather than a wrong answer.
    private fun shadowedPorts(): List<String> {
        val published =
            Regex("""127\.0\.0\.1:(\d+)""")
                .findAll(listOf(serverUrl, decliningUrl).joinToString(" "))
                .map { it.groupValues[1] }
                .toMutableSet()
        listOf("konekt.stand.metrik", "konekt.stand.tracy", "konekt.stand.katcher").forEach { key ->
            System
                .getProperty(key)
                ?.substringAfterLast(':')
                ?.takeWhile { it.isDigit() }
                ?.let(published::add)
        }

        return published.filter { it.isNotBlank() }.mapNotNull { port ->
            val holders =
                try {
                    ProcessBuilder("lsof", "-nP", "-iTCP:$port", "-sTCP:LISTEN")
                        .redirectErrorStream(true)
                        .start()
                        .let { process ->
                            val text = process.inputStream.bufferedReader().readText()
                            process.waitFor()
                            text
                        }.lines()
                        .drop(1)
                        .filter { it.isNotBlank() }
                } catch (unavailable: Exception) {
                    return emptyList()
                }

            // Docker itself is the expected holder. Anything else on the same port is the finding —
            // and it is a finding even when docker is there too, because that is exactly the shape:
            // one process on the wildcard address and one on the loopback, with the loopback winning.
            val strangers = holders.filterNot { it.contains("docker", ignoreCase = true) }
            strangers.firstOrNull()?.let { "port $port is held by: ${it.trim().replace(Regex("\\s+"), " ")}" }
        }
    }
}
