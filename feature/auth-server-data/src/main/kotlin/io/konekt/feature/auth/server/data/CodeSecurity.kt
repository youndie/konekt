package io.konekt.feature.auth.server.data

import io.konekt.feature.auth.server.domain.CodeGenerator
import io.konekt.feature.auth.server.domain.CodeHasher
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// SecureRandom, not Random. A predictable one-time code is not a weaker code — it is no code, because
// anyone who can predict it does not need to receive it.
class SecureCodeGenerator(
    private val random: SecureRandom = SecureRandom(),
) : CodeGenerator {
    override fun generate(length: Int): String = buildString { repeat(length) { append(random.nextInt(10)) } }
}

// Keyed, and the reason matters more than the code.
//
// A six-digit code has a million possibilities. A plain SHA-256 of one is reversed by hashing all
// million, which is milliseconds — so an unkeyed digest of an OTP protects nothing at all against
// anybody holding the table. An HMAC under a secret the database does not contain does protect it,
// for exactly as long as the secret stays out of the same dump.
//
// That is the whole claim, and it is worth being precise about: this defends against a leaked
// database, not against a compromised server. The alternative — storing the code as it is — defends
// against neither, and costs nothing to avoid.
class Hmac256CodeHasher(
    secret: String,
) : CodeHasher {
    private val key = SecretKeySpec(secret.toByteArray(), ALGORITHM)

    override fun hash(code: String): String =
        Mac
            .getInstance(ALGORITHM)
            .apply { init(key) }
            .doFinal(code.toByteArray())
            .joinToString("") { byte -> ((byte.toInt() and 0xFF) + 0x100).toString(16).substring(1) }

    private companion object {
        const val ALGORITHM = "HmacSHA256"
    }
}
