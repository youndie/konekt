-- WHO IS COMPENSATING THIS SAGA. One row per abandoned saga, taken before the work starts.
--
-- `B-64` found a purchase abandoned at its confirmation being refunded once per running replica and
-- closed it at the INVARIANT: a unique index on `ledger_entry (order_id, kind)`, the entry written
-- before the balance moves, and `23505` swallowed because a second compensation is not an error. The
-- money has been correct under any number of sweepers ever since.
--
-- What it left is the work. Each replica's sweeper walks the same sagas, opens the same transactions,
-- calls the same compensation chain and reaches the same unique-index violation. Invisible on this
-- build — one replica, a demonstration's worth of sagas — and not invisible in the two places a
-- reference is read from: a compensation that talks to an external system would call it twice (the
-- payment mock is idempotent by construction and a real PSP's `refund` is not), and the counter
-- `PetichEngineMetrics` exposes reports one number per replica for one reversal.
--
-- A CLAIM AND NOT A LOCK, and not a leader election: one row, one conditional write, and the loser
-- does nothing and knows it. The same arbitration the refresh-token rotation already uses here.
--
-- THE UNIQUE INDEX STAYS. It is the invariant and this is the optimisation; a claim that fails open
-- must still land on a correct outcome, which is what makes the lease below safe.
SET lock_timeout = '3s';

CREATE TABLE saga_sweep_claim (
    -- petich's saga id. Not a foreign key: the saga table is petich's own schema, and a constraint
    -- from konekt's table into it would make a library's migration this build's problem.
    saga_id    VARCHAR(64) NOT NULL,
    -- WHEN IT WAS TAKEN, which is what makes the claim a LEASE rather than a headstone.
    --
    -- A permanent claim is wrong in the one case that matters: a sweeper that wins and then dies
    -- mid-compensation would leave the saga claimed for ever and nobody would retry it — which is
    -- worse than the duplicated work this table exists to remove. Re-claimable after the lease, so
    -- the failure mode is "compensated late" rather than "never".
    claimed_at BIGINT      NOT NULL,
    CONSTRAINT pk_saga_sweep_claim PRIMARY KEY (saga_id)
);
