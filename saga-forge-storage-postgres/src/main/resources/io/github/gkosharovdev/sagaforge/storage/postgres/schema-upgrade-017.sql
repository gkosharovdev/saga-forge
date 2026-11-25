-- Saga Forge PostgreSQL schema upgrade for specs/017-saga-deadlines (data-model.md "Postgres").
-- Applies to a database created from (or upgraded to) the schema.sql of specs/011 to 012. Applied by the host
-- application; this module ships no migration tool. Run it once, in one transaction, while no engine is running;
-- running it again changes nothing.

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
