-- Every tariff a subscriber has been on, and the one they asked for next.
--
-- A LOG RATHER THAN A COLUMN, and that is the decision worth writing down. "Which tariff is this
-- subscriber on" could be a column on `subscriber` updated in place; then the answer to "since when"
-- and "what were they on before" is gone, and a change that is still awaiting confirmation has
-- nowhere to sit that is not also the current answer. Appending instead makes both free, and it
-- matches how money is already recorded here: `ledger_entry` is the same shape for the same reason.
--
-- The current tariff is the newest APPLIED row, and a subscriber with no rows is on the default one.
-- That means a new subscriber needs no backfill, which is what keeps this migration an expand.

-- EVERY DDL STATEMENT IN THIS FILE WAITS AT MOST THREE SECONDS FOR ITS LOCK. An ALTER or a CREATE
-- that waits behind a long read queues every LATER reader behind itself, and a blocked table is
-- downtime whatever the deploy is doing.
SET lock_timeout = '3s';

CREATE TABLE tariff_change (
    id             VARCHAR(64) NOT NULL,
    -- The saga that owns this row. One change per saga, so a retry cannot leave two.
    change_id      VARCHAR(64) NOT NULL,
    subscriber_id  VARCHAR(64) NOT NULL,
    -- Where they were. Recorded rather than derived, because deriving it means reading the log at
    -- apply time and a log that is read to be written is a race with itself.
    from_tariff_id VARCHAR(64) NOT NULL,
    to_tariff_id   VARCHAR(64) NOT NULL,
    -- pending | applied | cancelled. A VARCHAR and not an enum type: adding a state must not be a
    -- schema change with a lock on it.
    status         VARCHAR(16) NOT NULL,
    -- WHEN IT BITES, and it is a boundary rather than now: an immediate change makes proration the
    -- centre of the feature, and proration is arithmetic this build has nothing to say about.
    effective_at   BIGINT      NOT NULL,
    created_at     BIGINT      NOT NULL,
    CONSTRAINT pk_tariff_change PRIMARY KEY (id),
    CONSTRAINT uq_tariff_change_change_id UNIQUE (change_id),
    CONSTRAINT fk_tariff_change_subscriber_id__id FOREIGN KEY (subscriber_id) REFERENCES subscriber (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT
);

-- The two questions this table is asked: "what is this subscriber on" and "is anything pending for
-- them". Both filter by subscriber and by status, so one index serves both.
CREATE INDEX idx_tariff_change_subscriber_id_status ON tariff_change (subscriber_id, status);
