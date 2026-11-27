-- Saga Forge PostgreSQL schema version 2 (specs/011-saga-outcome-events).
-- Applied by Flyway from classpath:io/github/gkosharovdev/sagaforge/storage/postgres/migration under Saga Forge's
-- own history table (saga_forge_schema_history by default); see docs/guide/schema.md. Released versions are
-- frozen: a schema change adds a new version.

-- The terminal status of a failed compensation is renamed REQUIRES_INTERVENTION -> COMPENSATION_FAILED.
ALTER TABLE saga_instance DROP CONSTRAINT IF EXISTS trigger_iff_compensation;

UPDATE saga_instance SET status = 'COMPENSATION_FAILED' WHERE status = 'REQUIRES_INTERVENTION';

ALTER TABLE saga_instance ADD CONSTRAINT trigger_iff_compensation
    CHECK ((status IN ('COMPENSATING', 'COMPENSATED', 'COMPENSATION_FAILED'))
        = (compensation_trigger IS NOT NULL));

-- The separate REQUIRES_INTERVENTION history entry is gone: the COMPENSATION_FAILED entry written with it is the
-- instance's terminal entry. It was always the last entry of its instance, so no sequence gap is left behind.
DELETE FROM saga_lifecycle_history WHERE kind = 'REQUIRES_INTERVENTION';
