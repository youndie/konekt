package io.konekt.feature.roaming.server.data

import io.konekt.db.tables.SubscriberTable
import org.jetbrains.exposed.v1.core.Table

// Mirrors V10__roaming_package.sql. The column names are the migration's, not Exposed's defaults —
// this object describes a table that already exists rather than one Exposed is asked to create.
object RoamingPackageTable : Table("roaming_package") {
    val id = varchar("id", 64)
    val orderId = varchar("order_id", 64)
    val subscriberId = varchar("subscriber_id", 64).references(SubscriberTable.id)
    val zone = varchar("zone", 16)
    val limitMb = long("limit_mb")
    val remainingMb = long("remaining_mb")
    val validForDays = long("valid_for_days")
    val purchasedAt = long("purchased_at")
    val activatedAt = long("activated_at").nullable()
    val expiresAt = long("expires_at").nullable()

    override val primaryKey = PrimaryKey(id, name = "pk_roaming_package")

    init {
        // The saga's idempotence, at the level that can actually enforce it. A retried EXECUTION step
        // grants once because the database refuses the second row, not because the code remembered.
        uniqueIndex("uq_roaming_package_order_id", orderId)
    }
}
