package io.konekt.feature.esim.server.data

import io.github.youndie.kompot.wizard.core.WizardSession
import io.konekt.feature.esim.server.domain.EsimOrderDraft
import io.konekt.feature.esim.server.domain.EsimWizardRecord
import io.konekt.feature.esim.server.domain.EsimWizardSessions
import io.konekt.time.KonektClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update

class ExposedEsimWizardSessions(
    private val db: Database,
    private val clock: KonektClock,
) : EsimWizardSessions {
    // ITS OWN Json, not the application's. The application's carries every kompot component and
    // action, and what is written here is two small values of ours — a list of step ids and a draft.
    // Persisted text read back by a configuration it was not written by is the sort of coupling that
    // survives until somebody adds a serializers module and an old row stops decoding.
    private val storage = Json { encodeDefaults = true }

    private val stepIds = ListSerializer(String.serializer())

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = db) { block() }
        }

    override suspend fun create(record: EsimWizardRecord) {
        val now = clock.now().toEpochMilliseconds()

        dbQuery {
            EsimWizardSessionTable.insert {
                it[id] = record.id
                it[subscriberId] = record.subscriberId
                it[currentStep] = record.session.currentStepId
                it[history] = storage.encodeToString(stepIds, record.session.history)
                it[draft] = storage.encodeToString(EsimOrderDraft.serializer(), record.session.draft)
                it[finished] = record.session.isFinished
                it[createdAt] = now
                it[updatedAt] = now
            }
        }
    }

    override suspend fun find(wizardId: String): EsimWizardRecord? =
        dbQuery {
            EsimWizardSessionTable
                .selectAll()
                .where { EsimWizardSessionTable.id eq wizardId }
                .singleOrNull()
                ?.toDomain()
        }

    override suspend fun save(record: EsimWizardRecord) {
        dbQuery {
            EsimWizardSessionTable.update({ EsimWizardSessionTable.id eq record.id }) {
                it[currentStep] = record.session.currentStepId
                it[history] = storage.encodeToString(stepIds, record.session.history)
                it[draft] = storage.encodeToString(EsimOrderDraft.serializer(), record.session.draft)
                it[finished] = record.session.isFinished
                it[updatedAt] = clock.now().toEpochMilliseconds()
            }
        }
    }

    private fun ResultRow.toDomain(): EsimWizardRecord =
        EsimWizardRecord(
            id = this[EsimWizardSessionTable.id],
            subscriberId = this[EsimWizardSessionTable.subscriberId],
            session =
                WizardSession(
                    currentStepId = this[EsimWizardSessionTable.currentStep],
                    history = storage.decodeFromString(stepIds, this[EsimWizardSessionTable.history]),
                    draft = storage.decodeFromString(EsimOrderDraft.serializer(), this[EsimWizardSessionTable.draft]),
                    isFinished = this[EsimWizardSessionTable.finished],
                ),
        )
}
