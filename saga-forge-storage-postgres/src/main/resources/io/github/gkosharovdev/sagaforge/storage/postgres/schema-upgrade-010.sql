-- Saga Forge PostgreSQL schema upgrade for specs/010-otel-observability (contracts/message-context.md).
-- Applies to a database created from (or upgraded to) the schema.sql of specs/007 to 009. Applied by the host
-- application; this module ships no migration tool. Running it again changes nothing.

-- The trace context a stored message carries from the unit of work that stored it to the one that handles it.
ALTER TABLE saga_message_store ADD COLUMN IF NOT EXISTS trace_context JSONB;
