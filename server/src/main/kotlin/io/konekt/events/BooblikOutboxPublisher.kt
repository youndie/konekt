package io.konekt.events

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ru.workinprogress.booblik.TopicName
import ru.workinprogress.booblik.net.client.TopicHandle
import ru.workinprogress.petich.outbox.OutboxPublisher
import ru.workinprogress.petich.outbox.OutboxRecord

// The transport petich deliberately does not provide.
//
// `petich-outbox-core` gives at-least-once delivery with backoff and dead-lettering, and says
// outright that the transport is the application's. This is that transport, and it is the only place
// in this build where an event leaves the process.
//
// AT-LEAST-ONCE MEANS THE SAME EVENT ARRIVES TWICE. That is not a flaw to be worked around here — a
// relay that tried to be exactly-once would be inventing consensus — it is a contract the consumer
// honours by keying on the event id, which is `<orderId>:<type>` and therefore stable across
// redeliveries.
class BooblikOutboxPublisher(
    // THE HOLDER, NOT THE PRODUCER (`B-107`). Bound once at startup, a `Producer` is a wrapper
    // around one socket, and a broker pod being replaced left the relay retrying every pending row
    // against a connection that would never answer again — quietly, because the relay's whole
    // contract is to leave a row pending and try later. An outbox that never drains and an outbox
    // with nothing in it look the same from outside.
    private val broker: BrokerConnection,
    private val json: Json = Json,
) : OutboxPublisher {
    private val handles = mutableMapOf<String, TopicHandle>()

    // Which socket the cached handles belong to. A `TopicHandle` wraps the `Producer` it was made
    // from, so a reconnect makes every one of them stale — and a cache that outlives its connection
    // is the reason clearing it has to be deliberate rather than hopeful.
    private var seen = broker.generation

    override suspend fun publish(event: OutboxRecord) {
        val topic = handle(EventTopics.topicFor(event.type))

        // KEYED BY THE ORDER, not by the event id. Partitioning by key is what keeps every event
        // about one order in one partition, and a partition is the only place booblik promises an
        // order at all — so "reversed" cannot overtake "completed" for the same purchase. Keying by
        // the event id would spread one order's story across partitions and lose exactly that.
        val key = orderKeyOf(event)

        // Awaited, so a broker that refused the record raises here — the relay then leaves the row
        // pending and tries again. Fire-and-forget would mark it delivered on a write nobody
        // confirmed, which is the one way an outbox loses an event after all.
        //
        // A BROKEN SOCKET IS RETHROWN AND THE CONNECTION REPLACED. The relay's retry is what makes
        // this enough: this attempt fails and stays pending, and the next one runs on a fresh
        // connection. Retrying inline instead would put a second delivery attempt inside a method
        // whose caller already owns the retry.
        try {
            topic.send(event.payload.toByteArray(), key?.toByteArray()).await()
        } catch (failure: Exception) {
            if (BrokerConnection.isFinished(failure)) {
                seen = broker.reconnect(seen)
                handles.clear()
            }
            throw failure
        }
    }

    private suspend fun handle(topic: String): TopicHandle {
        // Somebody else's reconnect counts too: the usage consumer shares this broker and finds a
        // dead socket first about as often as the relay does.
        if (broker.generation != seen) {
            handles.clear()
            seen = broker.generation
        }
        return handles.getOrPut(topic) { broker.producer.topic(TopicName(topic)) }
    }

    // Read out of the payload rather than carried beside it: the outbox row is (id, type, payload)
    // and nothing else, so the key has to come from what is already there. A payload without one is
    // published unkeyed and round-robins, which is correct for an event that belongs to no order.
    private fun orderKeyOf(event: OutboxRecord): String? =
        runCatchingKey {
            json
                .parseToJsonElement(event.payload)
                .jsonObject["orderId"]
                ?.jsonPrimitive
                ?.content
        }

    private inline fun runCatchingKey(block: () -> String?): String? =
        try {
            block()
        } catch (malformed: IllegalArgumentException) {
            // A payload this relay cannot read is still a payload the consumer might. Refusing to
            // publish it would turn an unfamiliar shape into a stuck outbox.
            null
        }
}
