-- What a purchase leaves behind. The ORDER itself is not here: the order is the petich saga, which
-- already records every step it took, and a second table beside it would be a second source of truth
-- for the one thing this product is about.

-- The entitlement: what was bought, and whether it is usable yet.

-- EVERY DDL STATEMENT IN THIS FILE WAITS AT MOST THREE SECONDS FOR ITS LOCK.
--
-- An ALTER or a CREATE that waits behind a long read queues every LATER reader behind itself, and a
-- blocked table is downtime whatever the deploy is doing. Failing fast turns that into a migration
-- that did not run, which the deploy step reports and a person retries — the difference between a
-- release that stopped and a service that stopped.
SET lock_timeout = '3s';

CREATE TABLE entitlement (
    id             VARCHAR(64) NOT NULL,
    -- The saga id. One entitlement per order, so this is unique rather than merely indexed — two
    -- rows for one order would mean a step ran twice, and it is better to fail the write than to
    -- discover it later from a subscriber with two packages.
    order_id       VARCHAR(64) NOT NULL,
    subscriber_id  VARCHAR(64) NOT NULL,
    plan_id        VARCHAR(64) NOT NULL,
    -- pending | active | cancelled. A VARCHAR and not an enum type: adding a state must not be a
    -- schema change with a lock on it.
    status         VARCHAR(32) NOT NULL,
    price_minor    BIGINT      NOT NULL,
    currency       CHAR(3)     NOT NULL,
    created_at     BIGINT      NOT NULL,
    activated_at   BIGINT      NULL,
    CONSTRAINT pk_entitlement PRIMARY KEY (id),
    CONSTRAINT fk_entitlement_subscriber_id__id FOREIGN KEY (subscriber_id) REFERENCES subscriber (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT
);

CREATE UNIQUE INDEX uq_entitlement_order_id ON entitlement (order_id);
CREATE INDEX idx_entitlement_subscriber_id ON entitlement (subscriber_id);

-- Every movement of money, including the ones that were undone. A ledger rather than a balance
-- history: the balance is a column on `account` and this is why it holds what it holds, which is the
-- only way a subscriber can reconcile a reversal against their bank.
CREATE TABLE ledger_entry (
    id            VARCHAR(64) NOT NULL,
    account_id    VARCHAR(64) NOT NULL,
    -- Null for a movement with no order behind it — a top-up.
    order_id      VARCHAR(64) NULL,
    -- hold | release | capture | topup.
    kind          VARCHAR(32) NOT NULL,
    -- Signed as it moved: a hold is negative, a release positive. Storing the sign rather than
    -- deriving it from the kind means a sum over this table is the balance, with no case analysis
    -- that somebody can get wrong later.
    amount_minor  BIGINT      NOT NULL,
    currency      CHAR(3)     NOT NULL,
    created_at    BIGINT      NOT NULL,
    CONSTRAINT pk_ledger_entry PRIMARY KEY (id),
    CONSTRAINT fk_ledger_entry_account_id__id FOREIGN KEY (account_id) REFERENCES account (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT
);

CREATE INDEX idx_ledger_entry_account_id ON ledger_entry (account_id);
CREATE INDEX idx_ledger_entry_order_id ON ledger_entry (order_id);
