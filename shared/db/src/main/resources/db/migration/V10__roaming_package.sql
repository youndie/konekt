-- A package bought at home that does nothing until it is used abroad.
--
-- ITS OWN TABLE RATHER THAN A ROW IN `usage_counter`, and the reason is not tidiness. A counter is
-- live by definition: it has a limit, a remainder, and no notion of not having started. A roaming
-- package has three things a counter does not — a zone, an activation that may never happen, and an
-- expiry dated from that activation rather than from the purchase. Folding them together would put
-- three nullable columns on the one table every screen reads, and make "has this started" a question
-- about NULL.
--
-- It also leaves `usage_counter`'s key alone. That table is unique on (subscriber_id, kind), so a
-- second data package would have collided with the home one — and widening that key is an expand and
-- a contract a release apart, which is the right process and the wrong week.

-- EVERY DDL STATEMENT IN THIS FILE WAITS AT MOST THREE SECONDS FOR ITS LOCK. A statement waiting
-- behind a long read queues every LATER reader behind itself, and a blocked table is downtime
-- whatever the deploy is doing.
SET lock_timeout = '3s';

CREATE TABLE roaming_package (
    id             VARCHAR(64) NOT NULL,
    -- The order that bought it. Unique, so a retried saga cannot grant two — and so a compensation
    -- can find exactly what it granted.
    order_id       VARCHAR(64) NOT NULL,
    subscriber_id  VARCHAR(64) NOT NULL,
    -- Where it works: `tr`, `eu`, `us`. A VARCHAR and not an enum type, for the reason the counter's
    -- kind is one — adding a zone must not be a schema change with a lock on it.
    zone           VARCHAR(16) NOT NULL,
    limit_mb       BIGINT      NOT NULL,
    remaining_mb   BIGINT      NOT NULL,
    -- How long it runs ONCE STARTED. Copied off the plan at purchase rather than read back from the
    -- catalogue at activation: a package bought in March and first used in June must last what it was
    -- sold as, not what the plan says by then.
    valid_for_days BIGINT      NOT NULL,
    purchased_at   BIGINT      NOT NULL,
    -- NULL until the first byte is used abroad. This column IS the feature: the canvas says "the plan
    -- starts counting on first connection, not now", and a nullable timestamp is the smallest thing
    -- that can say it.
    activated_at   BIGINT,
    -- NULL until activation too, and dated FROM it. A package bought in March for a trip in June is
    -- not a package that expired in April.
    expires_at     BIGINT,
    CONSTRAINT pk_roaming_package PRIMARY KEY (id),
    CONSTRAINT uq_roaming_package_order_id UNIQUE (order_id),
    CONSTRAINT fk_roaming_package_subscriber_id__id FOREIGN KEY (subscriber_id) REFERENCES subscriber (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT
);

-- The two questions asked of this table: what has this subscriber got, and is anything of theirs live
-- in this zone. Both filter by subscriber and zone.
CREATE INDEX idx_roaming_package_subscriber_id_zone ON roaming_package (subscriber_id, zone);
