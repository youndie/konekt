-- The three entities every feature rests on. Feature-specific tables live with their features; these
-- three are touched by all of them — sign-in, balance, orders and the eSIM lifecycle — so they are
-- the shared core rather than anyone's.

-- EVERY DDL STATEMENT IN THIS FILE WAITS AT MOST THREE SECONDS FOR ITS LOCK.
--
-- An ALTER or a CREATE that waits behind a long read queues every LATER reader behind itself, and a
-- blocked table is downtime whatever the deploy is doing. Failing fast turns that into a migration
-- that did not run, which the deploy step reports and a person retries — the difference between a
-- release that stopped and a service that stopped.
SET lock_timeout = '3s';

CREATE TABLE subscriber (
    id            VARCHAR(64)  NOT NULL,
    -- E.164, digits only, no leading plus: one canonical form written once, because a number stored
    -- two ways is a subscriber who can sign in twice and own two balances.
    msisdn        VARCHAR(20)  NOT NULL,
    display_name  VARCHAR(120) NULL,
    created_at    BIGINT       NOT NULL,
    CONSTRAINT pk_subscriber PRIMARY KEY (id)
);

-- A constraint rather than an index, because it is a rule and not an optimisation: two subscribers
-- with one number is the corruption that sign-in cannot recover from.
CREATE UNIQUE INDEX uq_subscriber_msisdn ON subscriber (msisdn);

CREATE TABLE account (
    id             VARCHAR(64) NOT NULL,
    subscriber_id  VARCHAR(64) NOT NULL,
    -- Money as two columns, matching the wire form of the Money type: minor units and an ISO code,
    -- never a decimal and never a formatted string. The exponent belongs to the currency — a hundred
    -- is right for the dollar and wrong for the yen — and nothing in a BIGINT says which one it is
    -- holding, which is exactly why the code travels beside it.
    --
    -- No DEFAULT on the currency, deliberately. This build runs in USD (Currency.DEFAULT) and an
    -- account is always created with a currency by code; a column default would quietly fill in a
    -- row that a bug had failed to give one, and a balance in the wrong currency is worse than a
    -- write that fails.
    balance_minor  BIGINT      NOT NULL DEFAULT 0,
    currency       CHAR(3)     NOT NULL,
    created_at     BIGINT      NOT NULL,
    CONSTRAINT pk_account PRIMARY KEY (id),
    -- The name is Exposed's own convention rather than a nicer one, and deliberately: the Exposed
    -- Gradle plugin diffs future changes against the Table definitions, and a constraint whose name
    -- does not match what Exposed would generate shows up in every diff as a drop-and-recreate pair.
    CONSTRAINT fk_account_subscriber_id__id FOREIGN KEY (subscriber_id) REFERENCES subscriber (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT
);

CREATE INDEX idx_account_subscriber_id ON account (subscriber_id);

CREATE TABLE esim (
    id               VARCHAR(64)  NOT NULL,
    subscriber_id    VARCHAR(64)  NOT NULL,
    -- 19 or 20 digits. Unique because an ICCID identifies a profile globally, and a duplicate here
    -- means the SM-DP+ mock issued the same profile twice.
    iccid            VARCHAR(20)  NOT NULL,
    -- One of the lifecycle words the wire uses (EsimStatuses in :shared:components). A VARCHAR and
    -- not an enum type: adding a state must not be a schema change with a lock on it.
    status           VARCHAR(32)  NOT NULL,
    -- The LPA string. It is a credential — anyone holding it can install the profile — so it never
    -- becomes a URL and never leaves this table except into a QR the client draws itself.
    activation_code  VARCHAR(255) NULL,
    created_at       BIGINT       NOT NULL,
    activated_at     BIGINT       NULL,
    CONSTRAINT pk_esim PRIMARY KEY (id),
    CONSTRAINT fk_esim_subscriber_id__id FOREIGN KEY (subscriber_id) REFERENCES subscriber (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT
);

CREATE UNIQUE INDEX uq_esim_iccid ON esim (iccid);
CREATE INDEX idx_esim_subscriber_id ON esim (subscriber_id);
