-- Saga Forge PostgreSQL schema version 3 (specs/017-saga-deadlines).
-- Applied by Flyway from classpath:io/github/gkosharovdev/sagaforge/storage/postgres/migration under Saga Forge's
-- own history table (saga_forge_schema_history by default); see docs/guide/schema.md. Released versions are
-- frozen: a schema change adds a new version.

-- Messages with a due time, and cancelled messages.
ALTER TABLE saga_message_store ADD COLUMN IF NOT EXISTS not_before TIMESTAMPTZ;

ALTER TABLE saga_message_store DROP CONSTRAINT IF EXISTS saga_message_store_status_check;
ALTER TABLE saga_message_store ADD CONSTRAINT saga_message_store_status_check
    CHECK (status IN ('UNCONSUMED', 'CONSUMED', 'SET_ASIDE', 'CANCELLED'));

ALTER TABLE saga_message_store DROP CONSTRAINT IF EXISTS consumed_at_iff_consumed;
ALTER TABLE saga_message_store ADD CONSTRAINT consumed_at_iff_consumed
    CHECK ((status IN ('CONSUMED', 'CANCELLED')) = (consumed_at IS NOT NULL));

DROP INDEX IF EXISTS saga_message_store_consumed;
CREATE INDEX IF NOT EXISTS saga_message_store_consumed
    ON saga_message_store (consumed_at) WHERE status IN ('CONSUMED', 'CANCELLED');

-- Open deadlines of an instance. Existing instances have none.
ALTER TABLE saga_instance ADD COLUMN IF NOT EXISTS open_deadlines JSONB NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE saga_instance DROP CONSTRAINT IF EXISTS open_deadlines_only_while_active;
ALTER TABLE saga_instance ADD CONSTRAINT open_deadlines_only_while_active
    CHECK (open_deadlines = '{}'::jsonb OR status IN ('CREATED', 'RUNNING'));
