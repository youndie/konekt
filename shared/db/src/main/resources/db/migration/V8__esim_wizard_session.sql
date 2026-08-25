-- One run of the eSIM install wizard.
--
-- In a table and not in a map, and the reason is not scale. A run that has already issued a profile
-- and is then lost to a restart leaves the profile existing and the subscriber's screen not; a second
-- replica answers "no such wizard" to a button its neighbour drew. Both fail silently, and both cost
-- somebody a profile slot they cannot get back without support.

CREATE TABLE esim_wizard_session (
    id            VARCHAR(64) NOT NULL,
    subscriber_id VARCHAR(64) NOT NULL,
    current_step  VARCHAR(32) NOT NULL,
    -- The stack of PREVIOUS step ids, as JSON. wizard-core keeps the whole stack so that Back leads
    -- where the subscriber actually came from rather than where the resolver would route now — the
    -- two differ the moment the graph branches, and storing only the previous step would make Back a
    -- guess that is usually right.
    history       TEXT        NOT NULL,
    -- The accumulated draft, as JSON. Opaque to the schema on purpose: a field added to the draft
    -- must not be a migration with a lock on it.
    draft         TEXT        NOT NULL,
    finished      BOOLEAN     NOT NULL,
    created_at    BIGINT      NOT NULL,
    updated_at    BIGINT      NOT NULL,
    CONSTRAINT pk_esim_wizard_session PRIMARY KEY (id),
    CONSTRAINT fk_esim_wizard_session_subscriber_id__id FOREIGN KEY (subscriber_id) REFERENCES subscriber (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT
);

CREATE INDEX idx_esim_wizard_session_subscriber_id ON esim_wizard_session (subscriber_id);
