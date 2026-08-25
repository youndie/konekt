package io.konekt.feature.auth.server.data

import io.konekt.feature.auth.server.domain.CodeGenerator
import io.konekt.feature.auth.server.domain.CodeHasher
import io.konekt.feature.auth.server.domain.OtpDelivery
import io.konekt.feature.auth.server.domain.OtpPolicy
import io.konekt.feature.auth.server.domain.OtpRepository
import io.konekt.feature.auth.server.domain.RequestOtpUseCase
import io.konekt.feature.auth.server.domain.SessionIssuer
import io.konekt.feature.auth.server.domain.SubscriberRepository
import io.konekt.feature.auth.server.domain.VerifyOtpUseCase
import io.konekt.http.SubscriberPrincipal
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.jwt
import org.jetbrains.exposed.v1.jdbc.Database
import org.koin.dsl.module

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
    single<SessionIssuer> { JwtSessionIssuer(jwt, get()) }

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
}

// The session tier. Installed here rather than in the composition root because the shape of a token
// is this feature's business; what the root decides is which routes sit inside `authenticate`.
fun Application.configureAuthentication(jwt: JwtConfig) {
    install(Authentication) {
        jwt(AUTH_JWT) {
            realm = jwt.issuer
            verifier(JwtSessionIssuer.verifier(jwt))
            validate { credential ->
                // The verifier already refused a refresh token, an expired one, a wrong issuer and a
                // wrong audience. What is left is the subject, and a token without one is a token
                // this server did not issue.
                credential.payload.subject?.let(::SubscriberPrincipal)
            }
        }
    }
}
