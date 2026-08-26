package io.konekt.roaming.dev

import io.konekt.events.BrokerConnection
import io.konekt.events.EventTopics
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.koin.ktor.ext.inject
import ru.workinprogress.booblik.TopicName

// "I have landed." The one thing in this product that starts a roaming package.
//
// IT EXISTS BECAUSE THE SIMULATOR DELIBERATELY WILL NOT. A package that started itself five seconds
// after purchase would make the state this feature is about — bought, not counting — unobservable,
// so the simulator only keeps started trips running and the start is somebody's decision. In a real
// MVNO that decision arrives from the network as a first-attach notification; here it arrives from
// whoever is giving the demonstration.
//
// AUTH TIER: public, and only mounted where `DEV_SCREENS` is set — the same tier and the same gate as
// the other development routes, with `DevRoutesAreNotProductionTest` keeping it off a real build. It
// takes the subscriber as a parameter rather than from a token for that reason: there is no token.
@Resource("/api/v1/dev/roaming/arrive")
class ArriveResource(
    val subscriberId: String,
    val zone: String,
)

fun Route.roamingArriveRoutes() {
    val json by inject<Json>()
    val connection by inject<BrokerConnection>()

    post<ArriveResource> { params ->
        // PUBLISHED TO THE BROKER, not written to the table. The whole value of this route is that it
        // enters the same pipe real traffic would — broker, consumer, package, realtime, screen. A
        // version of it that called `RoamingPackages.consume` directly would light the card up and
        // prove nothing about the path.
        connection.producer.topic(TopicName(EventTopics.USAGE)).send(
            json
                .encodeToString(
                    JsonObject.serializer(),
                    JsonObject(
                        mapOf(
                            "subscriberId" to JsonPrimitive(params.subscriberId),
                            "kind" to JsonPrimitive("data"),
                            // A single megabyte: this is an arrival, not a session. What it is for is
                            // crossing the line from dormant to started, and the amount it spends
                            // doing so should be small enough not to muddy the number on the card.
                            "units" to JsonPrimitive(1),
                            "zone" to JsonPrimitive(params.zone),
                        ),
                    ),
                ).toByteArray(),
            params.subscriberId.toByteArray(),
        )
        connection.producer.flush()

        // Accepted rather than OK, and the distinction is real: the consumer polls, so the package is
        // not started by the time this returns. Answering 200 would invite a caller to read the card
        // immediately and find it unchanged.
        call.respond(HttpStatusCode.Accepted)
    }
}
