-- petich 0.4.0.112 keeps two more things on the saga row than 0.4.0.88 did (petich B-53, B-54):
-- where an interrupted rollback resumes from, and what it will end as. Without the second, a refusal
-- whose rollback was interrupted finishes as FAILED, and a client repeating the request is told the
-- server broke rather than that it was refused.
--
-- Both nullable, so this is a catalogue change and not a table rewrite; the timeout is here because
-- every migration against this table bounds its wait on the lock it takes.
SET lock_timeout = '3s';

ALTER TABLE petiches ADD COLUMN IF NOT EXISTS compensating_from_index INT;
ALTER TABLE petiches ADD COLUMN IF NOT EXISTS compensating_towards VARCHAR(32);
