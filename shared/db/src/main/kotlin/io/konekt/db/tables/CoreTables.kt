package io.konekt.db.tables

import org.jetbrains.exposed.v1.core.Table

// The Exposed side of V2. Two representations of one schema exist here on purpose and neither is
// the copy of the other: the SQL is what runs against a database, and these objects are what the
// Exposed Gradle plugin diffs the next change against. `KonektSchemaTest` holds them to each other
// by asking Exposed whether anything is missing after Flyway has run, so a drift between the two
// fails a test rather than a deploy.
//
// They live under `io.konekt` because `exposed.migrations.tablesPackage` takes a single package
// root, and every feature module's tables have to sit under the same one.
//
// AND THEY LIVE IN A MODULE OF THEIR OWN, which is what the first feature forced. `subscriber` and
// `account` belong to no single feature — sign-in creates them, balance reads them, orders spend
// against them — so a feature that declared its own copy would be a second schema that agrees with
// this one until it does not. One declaration, and both `:server` and every feature's `-server-data`
// depend on it.

object SubscriberTable : Table("subscriber") {
    val id = varchar("id", 64)
    val msisdn = varchar("msisdn", 20).uniqueIndex("uq_subscriber_msisdn")
    val displayName = varchar("display_name", 120).nullable()
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(id, name = "pk_subscriber")
}

object AccountTable : Table("account") {
    val id = varchar("id", 64)
    val subscriberId = varchar("subscriber_id", 64).references(SubscriberTable.id).index("idx_account_subscriber_id")

    // The two halves of a Money (io.konekt.domain.Money), and they are never separated: a balance
    // read without its currency is a number that means nothing, and the exponent that turns it into
    // an amount belongs to the currency rather than to whoever divides by a hundred.
    val balanceMinor = long("balance_minor").default(0)
    val currency = char("currency", 3)

    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(id, name = "pk_account")
}

object EsimTable : Table("esim") {
    val id = varchar("id", 64)
    val subscriberId = varchar("subscriber_id", 64).references(SubscriberTable.id).index("idx_esim_subscriber_id")
    val iccid = varchar("iccid", 20).uniqueIndex("uq_esim_iccid")
    val status = varchar("status", 32)
    val activationCode = varchar("activation_code", 255).nullable()
    val createdAt = long("created_at")
    val activatedAt = long("activated_at").nullable()

    override val primaryKey = PrimaryKey(id, name = "pk_esim")
}

// One list, so a table added without being registered is invisible to the schema test — the same
// arrangement as konektWireNames for the component dictionary, and for the same reason.
val konektCoreTables: List<Table> = listOf(SubscriberTable, AccountTable, EsimTable)

// Every tariff a subscriber has been on, and the one they asked for next.
//
// A log rather than a column on `subscriber`: "since when" and "what before" come free, and a change
// still awaiting confirmation has somewhere to sit that is not also the current answer. The same
// shape `ledger_entry` uses, for the same reason.
object TariffChangeTable : Table("tariff_change") {
    val id = varchar("id", 64)

    // The saga that owns the row. Unique, so a retried saga cannot leave two.
    val changeId = varchar("change_id", 64).uniqueIndex("uq_tariff_change_change_id")
    val subscriberId =
        varchar("subscriber_id", 64)
            .references(SubscriberTable.id)
    val fromTariffId = varchar("from_tariff_id", 64)
    val toTariffId = varchar("to_tariff_id", 64)
    val status = varchar("status", 16)
    val effectiveAt = long("effective_at")
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(id, name = "pk_tariff_change")

    init {
        // Both questions this table is asked — what is current, and is anything pending — filter by
        // subscriber and status, so one index serves both.
        index("idx_tariff_change_subscriber_id_status", false, subscriberId, status)
    }

    const val PENDING = "pending"
    const val APPLIED = "applied"
    const val CANCELLED = "cancelled"
}
