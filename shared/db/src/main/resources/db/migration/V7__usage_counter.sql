-- What a subscriber has left, and of what.
--
-- One row per subscriber per kind. A purchase adds to it; the network takes from it. A history of
-- every decrement would be a second, much larger table answering a question nobody on this product
-- asks — the screen shows what is left, and the ledger already records what was paid for.

-- EVERY DDL STATEMENT IN THIS FILE WAITS AT MOST THREE SECONDS FOR ITS LOCK.
--
-- An ALTER or a CREATE that waits behind a long read queues every LATER reader behind itself, and a
-- blocked table is downtime whatever the deploy is doing. Failing fast turns that into a migration
-- that did not run, which the deploy step reports and a person retries — the difference between a
-- release that stopped and a service that stopped.
SET lock_timeout = '3s';

CREATE TABLE usage_counter (
    id               VARCHAR(64) NOT NULL,
    subscriber_id    VARCHAR(64) NOT NULL,
    -- data | minutes | messages. A VARCHAR and not an enum type: adding a kind must not be a schema
    -- change with a lock on it.
    kind             VARCHAR(16) NOT NULL,
    limit_units      BIGINT      NOT NULL,
    -- Clamped at zero by the UPDATE that decrements it, not by a CHECK: a check would refuse the
    -- write and leave the caller to handle a failure that has an obvious right answer.
    remaining_units  BIGINT      NOT NULL,
    created_at       BIGINT      NOT NULL,
    CONSTRAINT pk_usage_counter PRIMARY KEY (id),
    CONSTRAINT fk_usage_counter_subscriber_id__id FOREIGN KEY (subscriber_id) REFERENCES subscriber (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT
);

-- A rule rather than an optimisation: two rows of one kind for one subscriber is an allowance that
-- exists twice and is spent once.
CREATE UNIQUE INDEX uq_usage_counter_subscriber_kind ON usage_counter (subscriber_id, kind);
