-- Saga Forge PostgreSQL schema upgrade for specs/015-saga-operations (data-model.md "Storage: Postgres").
-- Applies to a database created from (or upgraded to) the schema.sql of specs/013 or 017. Applied by the host
-- application; this module ships no migration tool. Run it once, in one transaction, while no engine is running;
-- running it again changes nothing.

-- An operator's resolution, and how often an instance's compensation was resumed. Existing instances have none.
ALTER TABLE saga_instance ADD COLUMN IF NOT EXISTS resolution JSONB;
ALTER TABLE saga_instance ADD COLUMN IF NOT EXISTS compensation_round INT NOT NULL DEFAULT 0;

ALTER TABLE saga_instance DROP CONSTRAINT IF EXISTS resolution_only_when_failed;
ALTER TABLE saga_instance ADD CONSTRAINT resolution_only_when_failed
    CHECK (resolution IS NULL OR status IN ('FAILED', 'COMPENSATION_FAILED'));

ALTER TABLE saga_instance DROP CONSTRAINT IF EXISTS compensation_round_not_negative;
ALTER TABLE saga_instance ADD CONSTRAINT compensation_round_not_negative
    CHECK (compensation_round >= 0);

-- Operator listings page over (status, last_modified_at, saga_id).
CREATE INDEX IF NOT EXISTS saga_instance_status_modified
    ON saga_instance (status, last_modified_at, saga_id);

-- Who asked for an operator action and why; NULL for the engine's own entries.
ALTER TABLE saga_lifecycle_history ADD COLUMN IF NOT EXISTS operator TEXT;
ALTER TABLE saga_lifecycle_history ADD COLUMN IF NOT EXISTS operator_reason TEXT;
