package io.konekt.feature.esim.server.data

import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.decodeKompotComponent
import io.github.youndie.kompot.encodeKompotAction
import io.github.youndie.kompot.generated.generatedKonektSerializersModule
import io.github.youndie.kompot.generated.generatedStandardSerializersModule
import io.github.youndie.kompot.kompotCoreSerializersModule
import io.github.youndie.kompot.standard.ButtonComponent
import io.github.youndie.kompot.standard.ColumnComponent
import io.github.youndie.kompot.standard.TextComponent
import io.github.youndie.kompot.standard.kompotStandardSerializersModule
import io.github.youndie.kompot.wizard.core.WizardTransition
import io.konekt.components.EsimQrComponent
import io.konekt.components.EsimStatuses
import io.konekt.components.StepMeterComponent
import io.konekt.components.konektWalk
import io.konekt.db.tables.EsimTable
import io.konekt.db.tables.SubscriberTable
import io.konekt.feature.esim.shared.api.EsimWizardStepAction
import io.konekt.feature.esim.shared.api.esimActionsSerializersModule
import io.konekt.feature.shell.shared.api.shellActionsSerializersModule
import io.konekt.http.SubscriberPrincipal
import io.konekt.http.configureStatusPages
import io.konekt.testing.PostgresHarness
import io.konekt.time.KonektClock
import io.konekt.time.SystemClock
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.resources.Resources
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.plus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

// The flow end to end: real routes, a real Postgres, the real mock manager.
//
// IT DRIVES THE WIZARD BY REPLAYING THE SERVER'S OWN BUTTONS. Nothing here builds a request from a
// path and a transition it chose — each step posts back the action that arrived on the previous
// screen, which is what a client does. That covers the seam a screen test cannot reach: the action
// is registered by hand, so a missing serializers module encodes fine on the way out and fails to
// decode on the way back in, at runtime, on the one request that matters.
@OptIn(ExperimentalUuidApi::class)
class EsimWizardRoutingTest {
    private val clock: KonektClock = SystemClock

    private val json =
        Json {
            ignoreUnknownKeys = true
            classDiscriminator = "type"
            serializersModule =
                kompotCoreSerializersModule +
                kompotStandardSerializersModule +
                generatedStandardSerializersModule +
                generatedKonektSerializersModule +
                esimActionsSerializersModule +
                shellActionsSerializersModule
        }

    private lateinit var subscriberId: String

    private fun Application.testModule() {
        install(Koin) {
            modules(
                module {
                    single { clock }
                    single { json }
                },
                esimModule(PostgresHarness.database),
            )
        }
        install(ContentNegotiation) { json() }
        install(Resources)
        install(Authentication) {
            // A stand-in for the JWT provider, and the token IS the subscriber id — which makes
            // "somebody else's run" one header away instead of a second sign-in.
            bearer("test") {
                authenticate { credential -> SubscriberPrincipal(credential.token, "fam-1") }
            }
        }
        configureStatusPages()
        routing {
            authenticate("test") { esimWizardRoutes() }
        }
    }

    @BeforeTest
    fun seed() {
        PostgresHarness.truncateAll()
        val newSubscriberId = Uuid.random().toString()
        subscriberId = newSubscriberId
        transaction(PostgresHarness.database) {
            SubscriberTable.insert {
                it[id] = newSubscriberId
                it[msisdn] = "15550100042"
                it[createdAt] = 0
            }
        }
    }

    private fun seedProfiles(
        count: Int,
        status: String = EsimStatuses.READY,
    ) {
        val owner = subscriberId
        transaction(PostgresHarness.database) {
            repeat(count) { index ->
                EsimTable.insert {
                    it[id] = Uuid.random().toString()
                    it[EsimTable.subscriberId] = owner
                    it[iccid] = "894450000000000000$index"
                    it[EsimTable.status] = status
                    it[createdAt] = 0
                }
            }
        }
    }

    private suspend fun HttpClient.start(asSubscriber: String = subscriberId): KompotComponent =
        json.decodeKompotComponent(
            post("/api/v1/esim-wizard") {
                header(HttpHeaders.Authorization, "Bearer $asSubscriber")
            }.bodyAsText(),
        )

    private suspend fun HttpClient.send(
        action: EsimWizardStepAction,
        asSubscriber: String = subscriberId,
    ) = post("/api/v1/esim-wizard/step") {
        header(HttpHeaders.Authorization, "Bearer $asSubscriber")
        contentType(ContentType.Application.Json)
        // Encoded polymorphically, exactly as a client holding the button would send it.
        setBody(json.encodeKompotAction(action))
    }

    private suspend fun HttpClient.press(
        screen: KompotComponent,
        text: String,
        asSubscriber: String = subscriberId,
    ): KompotComponent {
        val button =
            assertNotNull(
                screen.all<ButtonComponent>().firstOrNull { it.text == text },
                "no \"$text\" on this screen: ${screen.all<ButtonComponent>().map { it.text }}",
            )
        val action = assertNotNull(button.action as? EsimWizardStepAction, "\"$text\" carries ${button.action}")

        val response = send(action, asSubscriber)
        assertEquals(HttpStatusCode.OK, response.status)
        return json.decodeKompotComponent(response.bodyAsText())
    }

    @Test
    fun `the whole install ends with a QR carrying the code that was issued`() =
        testApplication {
            application { testModule() }

            var screen = client.start()
            assertEquals(1, assertNotNull(screen.first<StepMeterComponent>()).current)

            screen = client.press(screen, "Continue")
            screen = client.press(screen, "Get my eSIM")

            // Step three: the QR frame.
            val qr = assertNotNull(screen.first<EsimQrComponent>(), "no QR after the profile was issued")
            assertEquals(3, assertNotNull(screen.first<StepMeterComponent>()).current)

            // The payload is the code in the database, not something the screen composed. These are
            // the two ends of the acceptance criterion and they are read separately on purpose.
            val stored =
                transaction(PostgresHarness.database) {
                    EsimTable
                        .selectAll()
                        .where { EsimTable.subscriberId eq subscriberId }
                        .single()
                }
            assertEquals(stored[EsimTable.activationCode], qr.payload)
            assertTrue(qr.payload.startsWith("LPA:1\$"), "not an activation code: ${qr.payload}")

            screen = client.press(screen, "I have scanned it")
            assertEquals(4, assertNotNull(screen.first<StepMeterComponent>()).current)
            // Still there for the subscriber whose camera failed.
            assertEquals(qr.payload, assertNotNull(screen.first<EsimQrComponent>()).payload)

            client.press(screen, "Done")

            val installed =
                transaction(PostgresHarness.database) {
                    EsimTable.selectAll().where { EsimTable.subscriberId eq subscriberId }.single()
                }
            assertEquals(EsimStatuses.INSTALLED, installed[EsimTable.status])
        }

    @Test
    fun `a full device is refused on step one and the wizard does not advance`() =
        testApplication {
            application { testModule() }
            seedProfiles(MockSmDpPlus.DEVICE_PROFILE_LIMIT)

            val screen = client.start()
            val refused = client.press(screen, "Continue")

            // The meter has not moved —
            assertEquals(1, assertNotNull(refused.first<StepMeterComponent>()).current)
            // — the reason is on the screen rather than in a status code —
            val banner =
                assertNotNull(
                    refused.all<TextComponent>().firstOrNull { it.id == "esim-wizard-refusal-text" },
                    "no refusal drawn",
                )
            assertTrue("8 eSIM profiles" in banner.text, banner.text)
            // — and nothing was ordered.
            val issued =
                transaction(PostgresHarness.database) {
                    EsimTable.selectAll().where { EsimTable.subscriberId eq subscriberId }.count()
                }
            assertEquals(MockSmDpPlus.DEVICE_PROFILE_LIMIT.toLong(), issued)
        }

    @Test
    fun `a terminated profile does not hold a slot`() =
        testApplication {
            application { testModule() }
            // Eight profiles, all finished with. A count that ignored the status would refuse
            // somebody holding none — and would read exactly like a correct refusal.
            seedProfiles(MockSmDpPlus.DEVICE_PROFILE_LIMIT, status = EsimStatuses.TERMINATED)

            val screen = client.start()
            val next = client.press(screen, "Continue")

            assertEquals(2, assertNotNull(next.first<StepMeterComponent>()).current)
        }

    @Test
    fun `somebody else's run answers 404 rather than 403`() =
        testApplication {
            application { testModule() }

            val screen = client.start()
            val action =
                assertNotNull(
                    screen.all<ButtonComponent>().first().action as? EsimWizardStepAction,
                )

            val stranger = Uuid.random().toString()
            val response = client.send(action, asSubscriber = stranger)

            // 403 would confirm the run exists, which is an enumeration oracle for anyone who wants
            // one.
            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `a body that is not a step of this wizard is refused, not mistaken for one`() =
        testApplication {
            application { testModule() }

            val response =
                client.post("/api/v1/esim-wizard/step") {
                    header(HttpHeaders.Authorization, "Bearer $subscriberId")
                    contentType(ContentType.Application.Json)
                    setBody("""{"type":"navigate","deeplink":"app://somewhere"}""")
                }

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        }

    @Test
    fun `the action that comes back is the one the wizard would accept next`() =
        testApplication {
            application { testModule() }

            val screen = client.start()
            val forward =
                assertNotNull(
                    screen
                        .all<ButtonComponent>()
                        .firstOrNull {
                            it.text == "Continue"
                        }?.action as? EsimWizardStepAction,
                )

            // Spelled out because the whole flow depends on it: the button the server drew carries a
            // Next, not a Finish, and carries the id of the run it was drawn for.
            assertEquals(WizardTransition.Next, forward.transition)
            assertTrue(forward.wizardId.isNotBlank())
        }

    private inline fun <reified T : KompotComponent> KompotComponent.first(): T? = all<T>().firstOrNull()

    private inline fun <reified T : KompotComponent> KompotComponent.all(): List<T> = walk().filterIsInstance<T>()

    // THE DICTIONARY'S OWN WALK, which descends a `surface` — the forward button is inside the
    // pinned footer since `B-115`, and a walk that stopped at columns and rows saw no button at all.
    private fun KompotComponent.walk(): List<KompotComponent> = konektWalk()
}
