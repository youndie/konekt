-- One outstanding code per number, keyed by the number itself. A new request replaces the row rather
-- than adding one: two live codes for a number doubles the guessing surface for no benefit anybody
-- asked for.

CREATE TABLE otp_challenge (
    msisdn         VARCHAR(20) NOT NULL,
    -- The hash, never the code. An unkeyed digest of six digits is reversed by hashing all million
    -- of them, so this is an HMAC under a server-side key — which defends a leaked database and not
    -- a compromised server. Storing the code plainly defends neither and costs nothing to avoid.
    code_hash      VARCHAR(64) NOT NULL,
    issued_at      BIGINT      NOT NULL,
    expires_at     BIGINT      NOT NULL,
    attempts_used  INTEGER     NOT NULL DEFAULT 0,
    -- Separate from expires_at because they mean different things: a code expires and can be
    -- replaced, a number is locked and cannot.
    locked_until   BIGINT      NULL,
    CONSTRAINT pk_otp_challenge PRIMARY KEY (msisdn)
);
