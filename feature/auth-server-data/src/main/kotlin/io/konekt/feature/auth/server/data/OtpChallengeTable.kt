package io.konekt.feature.auth.server.data

import org.jetbrains.exposed.v1.core.Table

// One outstanding challenge per number, keyed by the number itself. A new request replaces the old
// row rather than adding one: two live codes for a number doubles the guessing surface for no
// benefit.
//
// Under io.konekt so the migration generator's single `tablesPackage` root covers it along with every
// other feature's tables.
object OtpChallengeTable : Table("otp_challenge") {
    val msisdn = varchar("msisdn", 20)

    // The hash, never the code. See Hmac256CodeHasher for what that is and is not worth.
    val codeHash = varchar("code_hash", 64)

    val issuedAt = long("issued_at")
    val expiresAt = long("expires_at")
    val attemptsUsed = integer("attempts_used").default(0)

    // Separate from expiresAt because they mean different things: a code expires and can be replaced,
    // a number is locked and cannot.
    val lockedUntil = long("locked_until").nullable()

    override val primaryKey = PrimaryKey(msisdn, name = "pk_otp_challenge")
}
