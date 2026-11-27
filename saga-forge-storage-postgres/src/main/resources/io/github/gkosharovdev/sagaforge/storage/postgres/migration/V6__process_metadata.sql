-- Saga Forge PostgreSQL schema version 6 (specs/019-process-metadata).
-- Applied by Flyway from classpath:io/github/gkosharovdev/sagaforge/storage/postgres/migration under Saga Forge's
-- own history table (saga_forge_schema_history by default); see docs/guide/schema.md. Released versions are
-- frozen: a schema change adds a new version.

-- Process metadata (specs/019-process-metadata data-model.md "Storage: Postgres"): the string map an instance was
-- started with, the metadata of the delivery that wrote each history entry, and the metadata each message carries.
-- Rows written before this version read as empty metadata.
ALTER TABLE saga_instance ADD COLUMN IF NOT EXISTS metadata JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE saga_lifecycle_history ADD COLUMN IF NOT EXISTS metadata JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE saga_message_store ADD COLUMN IF NOT EXISTS metadata JSONB NOT NULL DEFAULT '{}'::jsonb;
