package io.konekt.http

import io.konekt.domain.KonektException
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.principal

// The session family the caller's token belongs to, read from the verified token and never from the
// request. A route that took a family id from a body would end anybody's session for anybody who
// asked.
fun ApplicationCall.sessionFamilyId(): String =
    principal<SubscriberPrincipal>()?.sessionFamilyId
        ?: throw KonektException.Unauthorized()
