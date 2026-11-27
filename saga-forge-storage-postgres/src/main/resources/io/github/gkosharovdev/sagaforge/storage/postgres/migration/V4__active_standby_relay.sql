-- Saga Forge PostgreSQL schema version 4 (specs/013-active-standby-relay).
-- Applied by Flyway from classpath:io/github/gkosharovdev/sagaforge/storage/postgres/migration under Saga Forge's
-- own history table (saga_forge_schema_history by default); see docs/guide/schema.md. Released versions are
-- frozen: a schema change adds a new version.

-- The leadership of the store: one row, never deleted, so the fencing token only grows.
CREATE TABLE IF NOT EXISTS saga_message_store_leader (
    id             SMALLINT     PRIMARY KEY CHECK (id = 1),
    holder_id      VARCHAR(64)  NOT NULL,
    holder_host    VARCHAR(255) NOT NULL,
    fencing_token  BIGINT       NOT NULL CHECK (fencing_token >= 1),
    acquired_at    TIMESTAMPTZ  NOT NULL,
    expires_at     TIMESTAMPTZ  NOT NULL,
    CONSTRAINT expires_after_acquired CHECK (expires_at >= acquired_at)
);

-- Consumer heartbeats are replaced by the leadership.
DROP TABLE IF EXISTS saga_message_store_consumer;
