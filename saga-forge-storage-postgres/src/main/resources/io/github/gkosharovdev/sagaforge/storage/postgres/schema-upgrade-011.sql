-- Saga Forge PostgreSQL schema upgrade for specs/011-saga-outcome-events (research §6, FR-022).
-- Applies to a database created from (or upgraded to) the schema.sql of specs/006 to 010. Applied by the host
-- application; this module ships no migration tool. Run it once, in one transaction, while no engine is running;
-- running it again changes nothing.

-- The terminal status of a failed compensation is renamed REQUIRES_INTERVENTION -> COMPENSATION_FAILED.
ALTER TABLE saga_instance DROP CONSTRAINT IF EXISTS trigger_iff_compensation;

UPDATE saga_instance SET status = 'COMPENSATION_FAILED' WHERE status = 'REQUIRES_INTERVENTION';

ALTER TABLE saga_instance ADD CONSTRAINT trigger_iff_compensation
    CHECK ((status IN ('COMPENSATING', 'COMPENSATED', 'COMPENSATION_FAILED'))
        = (compensation_trigger IS NOT NULL));

-- The separate REQUIRES_INTERVENTION history entry is gone: the COMPENSATION_FAILED entry written with it is the
-- instance's terminal entry. It was always the last entry of its instance, so no sequence gap is left behind.
DELETE FROM saga_lifecycle_history WHERE kind = 'REQUIRES_INTERVENTION';
