package io.konekt.feature.usage.server.data

import io.konekt.db.tables.SubscriberTable
import org.jetbrains.exposed.v1.core.Table

object UsageCounterTable : Table("usage_counter") {
    val id = varchar("id", 64)
    val subscriberId = varchar("subscriber_id", 64).references(SubscriberTable.id)
    val kind = varchar("kind", 16)
    val limitUnits = long("limit_units")
    val remainingUnits = long("remaining_units")
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(id, name = "pk_usage_counter")

    init {
        uniqueIndex("uq_usage_counter_subscriber_kind", subscriberId, kind)
    }
}
