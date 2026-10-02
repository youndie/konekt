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

// What each order added to the counters above, under the order's name (B-130, `V15`). The counters
// are running totals and cannot say which purchase put what there; this can, so a grant run twice adds
// once and a rollback takes back exactly its own.
object UsageGrantTable : Table("usage_grant") {
    val orderId = varchar("order_id", 64)
    val kind = varchar("kind", 16)
    val subscriberId = varchar("subscriber_id", 64).references(SubscriberTable.id)
    val units = long("units")
    val grantedAt = long("granted_at")

    // Marked rather than deleted: a revoked order stays spent, so a late re-run cannot grant it again.
    val revokedAt = long("revoked_at").nullable()

    override val primaryKey = PrimaryKey(orderId, kind, name = "pk_usage_grant")
}
