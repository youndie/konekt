-- The four tables petich's Exposed repositories read and write. petich-postgres ships no DDL on
-- purpose — it takes a Database and does not know which DBMS is underneath — so the shapes here are
-- written from the Table definitions in that module and are checked against them by
-- PetichSchemaTest, which asks Exposed itself whether anything is still missing. A comment claiming
-- they match would rot; that test cannot.

CREATE TABLE petiches (
    id                        VARCHAR(255) NOT NULL,
    type                      VARCHAR(100) NOT NULL,
    -- enumerationByName, so the name travels rather than the ordinal: reordering the Kotlin enum
    -- must not silently change what a stored row means.
    current_phase             VARCHAR(50)  NOT NULL,
    current_interceptor_index INTEGER      NOT NULL,
    status                    VARCHAR(50)  NOT NULL,
    -- Exposed's json() column, not jsonb(): petich declares json<PetichPayload>(...), and the two
    -- are different Postgres types. Reading is the same and writing is not — a jsonb column
    -- normalises key order and drops duplicates, so a payload would come back subtly unequal to
    -- what a saga wrote.
    payload                   JSON         NOT NULL,
    enriched_payload          JSON         NOT NULL,
    -- The optimistic lock. petich updates WHERE version = version - 1, so this is the column two
    -- concurrent passes over one saga collide on.
    version                   BIGINT       NOT NULL,
    -- Epoch millis after which a suspended saga counts as expired. NULL means the step configured
    -- no TTL and the saga waits forever, which is the pre-TTL behaviour.
    suspended_until           BIGINT       NULL,
    CONSTRAINT pk_petiches PRIMARY KEY (id)
);

-- The sweeper's query filters on both columns, and petich's own comment on the column asks for this
-- index by name. Without it every sweep is a full scan of the busiest table in the system.
CREATE INDEX idx_petiches_status_suspended_until ON petiches (status, suspended_until);

CREATE TABLE outbox_events (
    id           VARCHAR(255) NOT NULL,
    type         VARCHAR(100) NOT NULL,
    -- Already-serialised JSON as TEXT, not JSON: the outbox stores an opaque payload it never looks
    -- inside, and a JSON column would validate — and therefore reject — a payload the broker would
    -- have carried fine.
    payload      TEXT         NOT NULL,
    status       VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    retry_count  INTEGER      NOT NULL DEFAULT 0,
    created_at   BIGINT       NOT NULL,
    CONSTRAINT pk_outbox_events PRIMARY KEY (id)
);

-- The relay polls for pending rows oldest-first. This index is not requested by a comment upstream;
-- it is requested by the shape of fetchPending, and it is here rather than added later because
-- adding an index to a live table is the expand half of a two-release change (see B-36).
CREATE INDEX idx_outbox_events_status_created_at ON outbox_events (status, created_at);

CREATE TABLE idempotency_keys (
    "key"                VARCHAR(255) NOT NULL,
    -- A fingerprint of the request, not the request: the guard detects a key reused with DIFFERENT
    -- parameters and deliberately stores no result to replay.
    request_fingerprint  VARCHAR(64)  NOT NULL,
    created_at           BIGINT       NOT NULL,
    CONSTRAINT pk_idempotency_keys PRIMARY KEY ("key")
);

CREATE TABLE scheduled_jobs (
    id                    VARCHAR(64) NOT NULL,
    owner_id              VARCHAR(64) NOT NULL,
    type                  VARCHAR(64) NOT NULL,
    payload               TEXT        NOT NULL,
    -- A closed set of words (ONCE/DAILY/WEEKLY/MONTHLY), not cron. A cron parser buys generality
    -- nobody asked for, and "0 0 3 * *" cannot be shown to a subscriber.
    recurrence            VARCHAR(16) NOT NULL,
    next_run_at           BIGINT      NOT NULL,
    last_run_at           BIGINT      NULL,
    active                BOOLEAN     NOT NULL DEFAULT TRUE,
    consecutive_failures  INTEGER     NOT NULL DEFAULT 0,
    CONSTRAINT pk_scheduled_jobs PRIMARY KEY (id)
);

-- Asked for by the column comment upstream, for the same reason as the petich one: the due-jobs
-- query filters on both.
CREATE INDEX idx_scheduled_jobs_active_next_run_at ON scheduled_jobs (active, next_run_at);
