package io.konekt.feature.auth.server.data

import io.konekt.db.tables.SubscriberTable
import org.jetbrains.exposed.v1.core.Table

object SessionFamilyTable : Table("session_family") {
    val id = varchar("id", 64)
    val subscriberId =
        varchar(
            "subscriber_id",
            64,
        ).references(SubscriberTable.id).index("idx_session_family_subscriber_id")
    val createdAt = long("created_at")
    val revokedAt = long("revoked_at").nullable()
    val revokedReason = varchar("revoked_reason", 32).nullable()

    override val primaryKey = PrimaryKey(id, name = "pk_session_family")
}

object RefreshTokenTable : Table("refresh_token") {
    val id = varchar("id", 64)
    val familyId = varchar("family_id", 64).references(SessionFamilyTable.id).index("idx_refresh_token_family_id")
    val issuedAt = long("issued_at")
    val expiresAt = long("expires_at")
    val usedAt = long("used_at").nullable()

    override val primaryKey = PrimaryKey(id, name = "pk_refresh_token")
}
