-- What a JWT cannot do on its own: end.
--
-- An access token is valid until it expires, so "log out" and "that token was stolen" mean nothing
-- unless the server has something to look at. These two tables are that something, and the cost is
-- one indexed lookup per authenticated request — chosen once and paid forever, which is why it is
-- written down rather than implied.

CREATE TABLE session_family (
    id              VARCHAR(64) NOT NULL,
    subscriber_id   VARCHAR(64) NOT NULL,
    created_at      BIGINT      NOT NULL,
    -- Null while the family is alive. Set by a logout, and by a refresh token turning up twice.
    revoked_at      BIGINT      NULL,
    -- 'logout' or 'reuse_detected'. Kept because the two are read differently later: one is a
    -- subscriber ending a session, the other is evidence that somebody else had their token.
    revoked_reason  VARCHAR(32) NULL,
    CONSTRAINT pk_session_family PRIMARY KEY (id),
    CONSTRAINT fk_session_family_subscriber_id__id FOREIGN KEY (subscriber_id) REFERENCES subscriber (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT
);

CREATE INDEX idx_session_family_subscriber_id ON session_family (subscriber_id);

CREATE TABLE refresh_token (
    -- The jti the token carries. Presented rather than looked up, so the token names its own row.
    id          VARCHAR(64) NOT NULL,
    family_id   VARCHAR(64) NOT NULL,
    issued_at   BIGINT      NOT NULL,
    expires_at  BIGINT      NOT NULL,
    -- The whole design in one column. A second exchange of one token finds this already set, and
    -- that is what "used twice" means — impossible for an honest holder, who replaced their token
    -- the moment they used it.
    used_at     BIGINT      NULL,
    CONSTRAINT pk_refresh_token PRIMARY KEY (id),
    CONSTRAINT fk_refresh_token_family_id__id FOREIGN KEY (family_id) REFERENCES session_family (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT
);

CREATE INDEX idx_refresh_token_family_id ON refresh_token (family_id);
