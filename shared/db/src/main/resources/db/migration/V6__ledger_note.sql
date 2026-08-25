-- Why a movement happened, when there is something to say.
--
-- Nullable and added, which makes this the EXPAND half of a change with no contract half needed: the
-- code that predates it writes nothing here and reads nothing from here, so old and new can serve
-- side by side through a rolling deploy. Every column added from here on wants to be able to say that
-- about itself (B-36).
ALTER TABLE ledger_entry ADD COLUMN note VARCHAR(255) NULL;
