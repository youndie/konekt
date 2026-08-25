package io.konekt.feature.realtime.shared.api

// The update stream's path, in one place because it cannot be a `@Resource`.
//
// Both halves of SSE take a plain string: Ktor's server builder is `sse(path)` and the client's is
// `serverSentEvents(urlString = …)`, and `ktor-client-resources` has no SSE builder to type either
// of them. So the rule this repository follows — no endpoint path exists as a string outside a
// `*-shared-api` — is kept the only way it can be: the string exists once, here, and both sides name
// the same constant.
//
// A module of its own rather than a corner of somebody else's, because the stream belongs to no
// feature: it carries whatever the server has to push, and today that is a usage counter and
// tomorrow an order's status.
object RealtimeStream {
    const val PATH = "/api/v1/realtime"

    // The topic a subscriber's updates travel on. Built here so that the server's `subscribe` and
    // whatever a client one day sends cannot spell it differently — and NOT sent by the client: the
    // server derives it from the verified token, because a stream addressed by a parameter is every
    // subscriber's screen for anybody who asks.
    fun topicOf(subscriberId: String): String = "subscriber:$subscriberId"
}
