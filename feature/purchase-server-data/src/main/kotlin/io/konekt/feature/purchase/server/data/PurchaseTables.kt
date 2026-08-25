package io.konekt.feature.purchase.server.data

import io.konekt.db.tables.AccountTable
import io.konekt.db.tables.SubscriberTable
import org.jetbrains.exposed.v1.core.Table

object EntitlementTable : Table("entitlement") {
    val id = varchar("id", 64)
    val orderId = varchar("order_id", 64).uniqueIndex("uq_entitlement_order_id")
    val subscriberId =
        varchar(
            "subscriber_id",
            64,
        ).references(SubscriberTable.id).index("idx_entitlement_subscriber_id")
    val planId = varchar("plan_id", 64)
    val status = varchar("status", 32)
    val priceMinor = long("price_minor")
    val currency = char("currency", 3)
    val createdAt = long("created_at")
    val activatedAt = long("activated_at").nullable()

    override val primaryKey = PrimaryKey(id, name = "pk_entitlement")
}

object LedgerEntryTable : Table("ledger_entry") {
    val id = varchar("id", 64)
    val accountId = varchar("account_id", 64).references(AccountTable.id).index("idx_ledger_entry_account_id")
    val orderId = varchar("order_id", 64).nullable().index("idx_ledger_entry_order_id")
    val kind = varchar("kind", 32)
    val amountMinor = long("amount_minor")
    val currency = char("currency", 3)
    val createdAt = long("created_at")
    val note = varchar("note", 255).nullable()

    override val primaryKey = PrimaryKey(id, name = "pk_ledger_entry")

    const val HOLD = "hold"
    const val RELEASE = "release"
    const val CAPTURE = "capture"

    // Zero-sum, and there to carry a sentence rather than an amount.
    const val DECLINE = "decline"
}
