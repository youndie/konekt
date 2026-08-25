package io.konekt.feature.esim.server.data

import io.konekt.feature.esim.server.domain.AdvanceEsimWizardUseCase
import io.konekt.feature.esim.server.domain.EsimIds
import io.konekt.feature.esim.server.domain.EsimRepository
import io.konekt.feature.esim.server.domain.EsimWizardSessions
import io.konekt.feature.esim.server.domain.SmDpPlus
import io.konekt.feature.esim.server.domain.StartEsimWizardUseCase
import org.jetbrains.exposed.v1.jdbc.Database
import org.koin.dsl.module
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
fun esimModule(database: Database) =
    module {
        // Explicit lambdas rather than singleOf/factoryOf: the reflective form resolves every
        // constructor parameter through the container, defaulted ones included, and MockSmDpPlus has
        // three of those.
        single<EsimRepository> { ExposedEsimRepository(database, get()) }
        single<EsimWizardSessions> { ExposedEsimWizardSessions(database, get()) }
        single<SmDpPlus> { MockSmDpPlus() }
        single<EsimIds> { EsimIds { Uuid.random().toString() } }

        factory { StartEsimWizardUseCase(get(), get()) }
        factory { AdvanceEsimWizardUseCase(get(), get(), get()) }
    }
