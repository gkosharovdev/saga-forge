-- Saga Forge PostgreSQL schema upgrade for specs/019-process-metadata (data-model.md "Storage: Postgres").
-- Applies to a database created from (or upgraded to) the schema.sql of specs/015 or 018. Applied by the host
-- application when it does not let Flyway migrate; run it once, in one transaction, while no engine is running;
-- running it again changes nothing.

-- Process metadata (specs/019-process-metadata data-model.md "Storage: Postgres"): the string map an instance was
-- started with, the metadata of the delivery that wrote each history entry, and the metadata each message carries.
-- Rows written before this version read as empty metadata.
ALTER TABLE saga_instance ADD COLUMN IF NOT EXISTS metadata JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE saga_lifecycle_history ADD COLUMN IF NOT EXISTS metadata JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE saga_message_store ADD COLUMN IF NOT EXISTS metadata JSONB NOT NULL DEFAULT '{}'::jsonb;
