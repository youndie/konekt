-- petich 0.4.0 keeps four things on the saga row that 0.1.0 did not.
--
-- The fill factor is an ACCESS EXCLUSIVE lock on the busiest table in the system, which is why the
-- timeout matters here more than on any other migration in this directory: a statement waiting for
-- that lock queues every later reader behind it.
SET lock_timeout = '3s';

-- How many times a rollback of this saga has given up, and the stamp the stranded-saga sweep reads.
ALTER TABLE petiches ADD COLUMN IF NOT EXISTS compensation_attempts INT NOT NULL DEFAULT 0;
ALTER TABLE petiches ADD COLUMN IF NOT EXISTS updated_at BIGINT NOT NULL DEFAULT 0;

-- The fingerprint of the members this saga has already run: a resume that cannot reproduce it stops
-- instead of running a different member.
ALTER TABLE petiches ADD COLUMN IF NOT EXISTS chain_fingerprint VARCHAR(64);

-- What each member recorded about what it did, by that member's key. This is what lets a
-- compensation tell "the step did not happen" from "it happened and recorded nothing" — the
-- question `AccountBalances.debit` answers today by reading our own ledger.
ALTER TABLE petiches ADD COLUMN IF NOT EXISTS step_records JSON NOT NULL DEFAULT '{}'::json;

ALTER TABLE petiches SET (fillfactor = 80);
