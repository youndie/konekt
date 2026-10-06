-- The contract half of B-132 (B-133): `saga_sweep_claim` loses its table.
--
-- `ClaimedSweep` was the only thing that wrote it, and B-132 removed it — petich's own claim on the
-- saga row replaced it. The table stayed for one release on purpose: during the roll that shipped
-- B-132, pods still on the previous release inserted into it, and a drop in the same release would have
-- failed their sweeps. v0.1.45 carried B-132 and wrote nothing here; this ships in a release after it.
--
-- contract: expanded in V16, and nothing has read it since v0.1.45

-- EVERY DDL STATEMENT IN THIS FILE WAITS AT MOST THREE SECONDS FOR ITS LOCK.
SET lock_timeout = '3s';

DROP TABLE IF EXISTS saga_sweep_claim;
