-- Why a movement happened, when there is something to say.
--
-- Nullable and added, which makes this the EXPAND half of a change with no contract half needed: the
-- code that predates it writes nothing here and reads nothing from here, so old and new can serve
-- side by side through a rolling deploy. Every column added from here on wants to be able to say that
-- about itself (B-36).

-- EVERY DDL STATEMENT IN THIS FILE WAITS AT MOST THREE SECONDS FOR ITS LOCK.
--
-- An ALTER or a CREATE that waits behind a long read queues every LATER reader behind itself, and a
-- blocked table is downtime whatever the deploy is doing. Failing fast turns that into a migration
-- that did not run, which the deploy step reports and a person retries — the difference between a
-- release that stopped and a service that stopped.
SET lock_timeout = '3s';

ALTER TABLE ledger_entry ADD COLUMN note VARCHAR(255) NULL;
