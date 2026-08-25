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
