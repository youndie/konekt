package io.konekt.client.net

import io.github.youndie.kompot.auth.UpdateSessionAction
import io.github.youndie.kompot.decodeKompotAction
import io.konekt.client.session.KonektSession
import io.konekt.client.session.SessionTokens
import io.konekt.feature.auth.shared.api.AuthSession
import io.konekt.feature.auth.shared.api.RefreshSessionRequest
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.AuthCircuitBreaker
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.resources.Resources
import io.ktor.client.plugins.resources.post
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

// The application's one HTTP client: the session, the wire's Json, and the typed paths.
//
// THE TOKENS ARE THE BEARER PLUGIN'S TO ATTACH, not ours. `loadTokens` reads the store and
// `refreshTokens` exchanges the refresh token, and everything about when to do either is the
// plugin's. Two things follow that are worth knowing before somebody reports them as bugs:
//
//   * `markAsRefreshTokenRequest()` is not decoration. Without it the refresh call goes back through
//     this same plugin carrying the token that just failed — measured, not assumed: the request
//     arrived at `/auth/session/refresh` with `Bearer stale` on it. The endpoint is public and
//     ignores it, so nothing breaks today, and the day it stops ignoring it a refresh cannot
//     succeed;
//   * a failed refresh clears the session rather than retrying. The server rotates refresh tokens
//     and detects reuse, so a second attempt with the same token ends the family: retrying is the
//     one response guaranteed to make things worse.
fun konektHttpClient(
    engine: HttpClientEngine,
    baseUrl: String,
    session: KonektSession,
    json: Json,
    configure: HttpClientConfig<*>.() -> Unit = {},
): HttpClient =
    HttpClient(engine) {
        install(ContentNegotiation) { json(json) }
        install(Resources)
        // The SSE plugin lives in ktor-client-core; there is no ktor-client-sse artefact.
        install(SSE)

        defaultRequest {
            url(baseUrl)
            contentType(ContentType.Application.Json)
        }

        install(Auth) {
            bearer {
                loadTokens {
                    session.load()?.let { BearerTokens(it.accessToken, it.refreshToken) }
                }

                refreshTokens {
                    // The refresh call is made on `client` — the plugin's own unauthenticated client
                    // — because sending the expired access token with a refresh is what turns one
                    // 401 into a loop.
                    session
                        .refresh { refreshToken ->
                            val response: HttpResponse =
                                client.post(AuthSession.Refresh(AuthSession())) {
                                    // The plugin's own escape hatch: without it this call re-enters
                                    // the Auth plugin and a 401 on the refresh would try to refresh
                                    // again. `markAsRefreshTokenRequest()` does not exist in Ktor
                                    // 3.5 — the attribute is the mechanism it wraps elsewhere.
                                    attributes.put(AuthCircuitBreaker, Unit)
                                    contentType(ContentType.Application.Json)
                                    setBody(RefreshSessionRequest(refreshToken))
                                }

                            // AN ACTION, not a DTO. Both `verify` and `refresh` answer an
                            // `update_session` through `respondKompotAction`, so what comes back is a
                            // polymorphic KompotAction with a "type" discriminator at its root —
                            // decoding it as a plain pair of strings would work today and break the
                            // moment the server sends any other action here.
                            if (response.status == HttpStatusCode.OK) {
                                (json.decodeKompotAction(response.bodyAsText()) as? UpdateSessionAction)
                                    ?.let { SessionTokens(it.accessToken, it.refreshToken) }
                            } else {
                                null
                            }
                        }?.let { BearerTokens(it.accessToken, it.refreshToken) }
                }
            }
        }

        configure()
    }
