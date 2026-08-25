package io.konekt.feature.auth.server.data

import io.konekt.feature.auth.server.domain.CodeGenerator
import io.konekt.feature.auth.server.domain.CodeHasher
import io.konekt.feature.auth.server.domain.IssueSessionUseCase
import io.konekt.feature.auth.server.domain.LogoutUseCase
import io.konekt.feature.auth.server.domain.OtpDelivery
import io.konekt.feature.auth.server.domain.OtpPolicy
import io.konekt.feature.auth.server.domain.OtpRepository
import io.konekt.feature.auth.server.domain.RefreshSessionUseCase
import io.konekt.feature.auth.server.domain.RequestOtpUseCase
import io.konekt.feature.auth.server.domain.SessionIssuer
import io.konekt.feature.auth.server.domain.SessionRepository
import io.konekt.feature.auth.server.domain.SubscriberRepository
import io.konekt.feature.auth.server.domain.TokenMinter
import io.konekt.feature.auth.server.domain.VerifyOtpUseCase
import io.konekt.http.SubscriberPrincipal
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.jwt
import org.jetbrains.exposed.v1.jdbc.Database
import org.koin.dsl.module
import org.koin.ktor.ext.getKoin

const val AUTH_JWT = "auth-jwt"

// Repositories are single, use cases are factory: a use case is stateless and cheap, and a repository
// holds the one thing worth sharing.
//
// Written as explicit lambdas rather than `singleOf(::…)`. The reflective form resolves EVERY
// constructor parameter through the container, INCLUDING ones with a Kotlin default value — the
// default is ignored, and a parameter whose type has no binding throws at runtime while the compiler
// says nothing. Both use cases here take a defaulted OtpPolicy, which is exactly that shape.
fun authModule(
    database: Database,
    jwt: JwtConfig,
    revealCodes: Boolean,
) = module {
    single { OtpPolicy() }

    single<OtpRepository> { ExposedOtpRepository(database) }
    single<SubscriberRepository> { ExposedSubscriberRepository(database, get()) }
    single<CodeGenerator> { SecureCodeGenerator() }
    // The pepper is the JWT secret, which is one secret doing two jobs — acceptable here because
    // both are "this deployment's server-side key" and neither leaves the process. A real deployment
    // with a key-management story gives them separate keys, and that is a row in the operator
    // material rather than a change here.
    single<CodeHasher> { Hmac256CodeHasher(jwt.secret) }
    single<SessionRepository> { ExposedSessionRepository(database) }
    single<TokenMinter> { JwtTokenMinter(jwt, get()) }
    // One instance under two types: SessionIssuer for signing in, and the concrete class for the
    // second half of a rotation. Bound by `get()` rather than constructed twice, so there is one
    // object and no chance of two clocks.
    single { IssueSessionUseCase(get(), get(), get()) }
    single<SessionIssuer> { get<IssueSessionUseCase>() }

    single { RevealedCodes() }
    single<OtpDelivery> {
        val delegates =
            buildList {
                add(LoggingOtpDelivery())
                if (revealCodes) add(get<RevealedCodes>())
            }
        CompositeOtpDelivery(delegates)
    }

    factory { RequestOtpUseCase(get(), get(), get(), get(), get(), get()) }
    factory { VerifyOtpUseCase(get(), get(), get(), get(), get(), get()) }
    factory { RefreshSessionUseCase(get(), get(), get(), get()) }
    factory { LogoutUseCase(get(), get()) }
}

// The session tier. Installed here rather than in the composition root because the shape of a token
// is this feature's business; what the root decides is which routes sit inside `authenticate`.
fun Application.configureAuthentication(jwt: JwtConfig) {
    install(Authentication) {
        jwt(AUTH_JWT) {
            realm = jwt.issuer
            verifier(JwtTokenMinter.accessVerifier(jwt))
            validate { credential ->
                // The verifier already refused a refresh token, an expired one, a wrong issuer and a
                // wrong audience. What it cannot know is whether the session still exists — a JWT is
                // valid until it expires, so logout and a detected theft mean nothing without this
                // lookup.
                //
                // THE COST IS ONE INDEXED READ PER AUTHENTICATED REQUEST, and it is the price of
                // logout working at all. The alternative is a short access lifetime and a logout that
                // takes effect when it expires, which is what "stateless logout" always means.
                val subject = credential.payload.subject ?: return@validate null
                val family = credential.payload.getClaim(FAMILY_CLAIM).asString() ?: return@validate null

                val sessions = getKoin().get<SessionRepository>()
                if (sessions.findFamily(family)?.isActive != true) return@validate null

                SubscriberPrincipal(subscriberId = subject, sessionFamilyId = family)
            }
        }
    }
}
