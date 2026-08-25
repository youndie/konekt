-- One outstanding code per number, keyed by the number itself. A new request replaces the row rather
-- than adding one: two live codes for a number doubles the guessing surface for no benefit anybody
-- asked for.

-- EVERY DDL STATEMENT IN THIS FILE WAITS AT MOST THREE SECONDS FOR ITS LOCK.
--
-- An ALTER or a CREATE that waits behind a long read queues every LATER reader behind itself, and a
-- blocked table is downtime whatever the deploy is doing. Failing fast turns that into a migration
-- that did not run, which the deploy step reports and a person retries — the difference between a
-- release that stopped and a service that stopped.
SET lock_timeout = '3s';

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
