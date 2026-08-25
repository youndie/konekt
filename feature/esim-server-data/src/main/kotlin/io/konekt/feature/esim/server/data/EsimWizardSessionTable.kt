package io.konekt.feature.esim.server.data

import io.konekt.db.tables.SubscriberTable
import org.jetbrains.exposed.v1.core.Table

// A wizard run, in a table rather than in a map.
//
// The obvious alternative is a `ConcurrentHashMap` keyed by run id, and it is wrong for two reasons
// that are not about scale. A restart between step two and step three loses a run that has already
// issued a profile — the profile exists, the subscriber's screen does not — and a second replica
// answers "no such wizard" to a button its neighbour drew. Both are silent.
object EsimWizardSessionTable : Table("esim_wizard_session") {
    val id = varchar("id", 64)
    val subscriberId =
        varchar("subscriber_id", 64)
            .references(SubscriberTable.id)
            .index("idx_esim_wizard_session_subscriber_id")

    val currentStep = varchar("current_step", 32)

    // The stack of PREVIOUS step ids, as JSON. wizard-core keeps it so that Back leads where the
    // subscriber actually came from rather than where the resolver would route now — the two differ
    // as soon as the graph branches, and storing only "the step before this one" would quietly make
    // Back a guess.
    val history = text("history")

    // The accumulated draft, as JSON. Opaque to the schema on purpose: a field added to
    // EsimOrderDraft must not be a migration with a lock on it.
    val draft = text("draft")

    val finished = bool("finished")
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(id, name = "pk_esim_wizard_session")
}
