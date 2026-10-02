-- What each order added to a subscriber's home allowance, under the order's name (B-130).
--
-- `usage_counter` holds one running total per subscriber and kind, and nothing in it says which
-- purchase added what. That left the purchase saga's grant with no name to be taken back by and no
-- way to tell a second run from a second purchase: a grant that committed and lost its answer stayed
-- with the subscriber when the purchase was rolled back, and a member petich ran again added the
-- plan a second time. The roaming branch never had either problem, because `roaming_package` is
-- keyed by its order; this gives the home branch the same key.
--
-- A grant writes its row and adds to the counter in ONE transaction, and adds only when the row was
-- new — so a re-run is a no-op. A revoke marks the row and subtracts exactly what it says, and only
-- when it was not marked yet — so a revoke of an order with nothing under it takes nothing from an
-- earlier plan, and a second revoke takes nothing twice.
--
-- A new table and nothing else, so the code already running never sees it.

-- EVERY DDL STATEMENT IN THIS FILE WAITS AT MOST THREE SECONDS FOR ITS LOCK. A statement waiting
-- behind a long read queues every LATER reader behind itself, and a blocked table is downtime
-- whatever the deploy is doing.
SET lock_timeout = '3s';

CREATE TABLE usage_grant (
    -- The purchase saga's id, which is the order's.
    order_id      VARCHAR(64) NOT NULL,
    -- data | minutes | messages, the same words `usage_counter.kind` uses.
    kind          VARCHAR(16) NOT NULL,
    subscriber_id VARCHAR(64) NOT NULL,
    units         BIGINT      NOT NULL,
    granted_at    BIGINT      NOT NULL,
    -- NULL while the allowance stands. Marked rather than deleted, so an order that was taken back
    -- cannot be granted again by a late re-run of the member that granted it.
    revoked_at    BIGINT,
    CONSTRAINT pk_usage_grant PRIMARY KEY (order_id, kind),
    CONSTRAINT fk_usage_grant_subscriber_id__id FOREIGN KEY (subscriber_id) REFERENCES subscriber (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT
);
