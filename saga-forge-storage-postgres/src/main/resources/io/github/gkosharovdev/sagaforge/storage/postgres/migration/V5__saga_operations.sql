-- Saga Forge PostgreSQL schema version 5 (specs/015-saga-operations).
-- Applied by Flyway from classpath:io/github/gkosharovdev/sagaforge/storage/postgres/migration under Saga Forge's
-- own history table (saga_forge_schema_history by default); see docs/guide/schema.md. Released versions are
-- frozen: a schema change adds a new version.

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
