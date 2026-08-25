package io.konekt.events

// Which topic an event goes to, decided in one place.
//
// booblik fixes its topic set at startup and will not create one on demand, so this map and the
// broker's `BOOBLIK_TOPICS` are two halves of one decision — and a type routed to a topic the broker
// does not have is a publish that fails forever rather than a topic that appears. `EventTopicsTest`
// is what holds the two halves together.
object EventTopics {
    const val ORDERS = "orders"
    const val USAGE = "usage"
    const val NOTIFICATIONS = "notifications"

    val all = listOf(ORDERS, USAGE, NOTIFICATIONS)

    // Prefix routing rather than an exhaustive map: a new `purchase.*` event should not have to be
    // added in two places to reach the topic every other purchase event already goes to. An
    // unrecognised prefix is a failure rather than a default, because a default here means an event
    // arriving somewhere nobody is listening.
    fun topicFor(eventType: String): String =
        when {
            eventType.startsWith("purchase.") -> ORDERS
            eventType.startsWith("usage.") -> USAGE
            eventType.startsWith("notification.") -> NOTIFICATIONS
            else -> error("no topic is configured for event type '$eventType'")
        }
}
