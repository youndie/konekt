-- Where a booblik consumer has got to, stored beside what it applies (B-134).
--
-- booblik keeps no consumer positions and will not (its research, R12), so the usage consumer kept
-- its own in memory and started every process at the end of the log: everything published while the
-- pod was down was never applied, and two replicas applied everything twice. Kept here, the position
-- is updated in the SAME transaction as the decrements it covers, guarded by the value the batch was
-- read from — booblik's `feature-consumer-position` recipe:
--
--     update consumer_position set next_offset = :next
--      where consumer = … and topic = … and partition_id = … and next_offset = :base
--
-- Zero rows means another reader already applied that batch, and the transaction rolls back.
--
-- A new table and nothing else, so the code already running never sees it.

-- EVERY DDL STATEMENT IN THIS FILE WAITS AT MOST THREE SECONDS FOR ITS LOCK.
SET lock_timeout = '3s';

CREATE TABLE consumer_position (
    -- Who reads: two consumers of one topic keep two positions.
    consumer     VARCHAR(64) NOT NULL,
    topic        VARCHAR(64) NOT NULL,
    partition_id INT         NOT NULL,
    -- The offset of the next record this consumer has not applied.
    next_offset  BIGINT      NOT NULL,
    CONSTRAINT pk_consumer_position PRIMARY KEY (consumer, topic, partition_id)
);
