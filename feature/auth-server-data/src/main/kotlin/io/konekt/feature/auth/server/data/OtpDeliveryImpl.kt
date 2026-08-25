package io.konekt.feature.auth.server.data

import io.konekt.feature.auth.server.domain.Msisdn
import io.konekt.feature.auth.server.domain.OtpDelivery
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

// Where a code goes instead of to a phone.
//
// The SMSC is outside this system's boundary and is not modelled at all: nothing is sent, and the
// code has to reach a person somehow or the product cannot be demonstrated. Two ways, both
// deliberate and both development-only.
class LoggingOtpDelivery : OtpDelivery {
    private val logger = LoggerFactory.getLogger("io.konekt.auth.otp")

    override fun deliver(
        msisdn: Msisdn,
        code: String,
    ) {
        // A log line carrying a one-time code is a credential in a log, and it is written here on
        // purpose because there is no SMSC. It is also the reason this class is named for what it
        // does rather than for what it is: nobody should be able to wire it in without noticing.
        logger.warn("DEV ONLY — otp for {} is {}", msisdn.value, code)
    }
}

// Keeps the last code per number so the development endpoint can read it back. In memory and
// unbounded by design: it holds one short string per number that has asked for a code since the
// process started, and it does not exist in a production build.
class RevealedCodes : OtpDelivery {
    private val codes = ConcurrentHashMap<String, String>()

    override fun deliver(
        msisdn: Msisdn,
        code: String,
    ) {
        codes[msisdn.value] = code
    }

    fun of(msisdn: Msisdn): String? = codes[msisdn.value]
}

// Both at once, because the log line is how a developer notices and the endpoint is how a test signs
// in. Composing rather than choosing keeps either from being the thing somebody forgot to enable.
class CompositeOtpDelivery(
    private val delegates: List<OtpDelivery>,
) : OtpDelivery {
    override fun deliver(
        msisdn: Msisdn,
        code: String,
    ) {
        delegates.forEach { it.deliver(msisdn, code) }
    }
}
