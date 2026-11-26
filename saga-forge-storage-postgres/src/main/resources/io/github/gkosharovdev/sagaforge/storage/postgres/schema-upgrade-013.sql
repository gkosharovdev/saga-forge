-- Saga Forge PostgreSQL schema upgrade for specs/013-active-standby-relay (data-model.md "Postgres").
-- Applies to a database created from (or upgraded to) the schema.sql of specs/017. Applied by the host
-- application; this module ships no migration tool. Run it once, in one transaction, while no engine is running;
-- running it again changes nothing.

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
