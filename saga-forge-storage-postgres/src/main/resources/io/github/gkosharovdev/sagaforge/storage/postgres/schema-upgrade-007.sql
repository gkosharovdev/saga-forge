-- Saga Forge PostgreSQL schema upgrade for specs/007-in-memory-transport (data-model.md).
-- Applies to a database created from (or upgraded to) the schema.sql of specs/006. Applied by the host application;
-- this module ships no migration tool. Running it again changes nothing.

-- Message log of the in-memory transport (specs/007-in-memory-transport data-model.md): a transactional outbox.
CREATE TABLE IF NOT EXISTS saga_message_log (
    position          BIGSERIAL    PRIMARY KEY,
    message_id        VARCHAR(255) NOT NULL UNIQUE,
    kind              VARCHAR(16)  NOT NULL CHECK (kind IN ('EVENT', 'COMMAND')),
    payload_type      VARCHAR(512) NOT NULL,
    payload           JSONB,
    command_id        JSONB,
    ordering_key      VARCHAR(1024),
    source_saga_type  VARCHAR(512),
    source_saga_id    VARCHAR(255),
    recorded_at       TIMESTAMPTZ  NOT NULL,
    status            VARCHAR(16)  NOT NULL CHECK (status IN ('UNCONSUMED', 'CONSUMED', 'SET_ASIDE')),
    attempts          INTEGER      NOT NULL DEFAULT 0,
    next_attempt_at   TIMESTAMPTZ  NOT NULL,
    last_failure      JSONB,
    leased_by        VARCHAR(64),
    leased_until     TIMESTAMPTZ,
    consumed_at       TIMESTAMPTZ,
    set_aside_reason  VARCHAR(4096),
    external_ref      VARCHAR(255),
    CONSTRAINT command_id_iff_command CHECK ((kind = 'COMMAND') = (command_id IS NOT NULL)),
    CONSTRAINT lease_together CHECK ((leased_by IS NULL) = (leased_until IS NULL)),
    CONSTRAINT consumed_at_iff_consumed CHECK ((status = 'CONSUMED') = (consumed_at IS NOT NULL)),
    CONSTRAINT reason_iff_set_aside CHECK ((status = 'SET_ASIDE') = (set_aside_reason IS NOT NULL))
);

CREATE INDEX IF NOT EXISTS saga_message_log_head
    ON saga_message_log (ordering_key, position) WHERE status = 'UNCONSUMED';
CREATE INDEX IF NOT EXISTS saga_message_log_due
    ON saga_message_log (next_attempt_at, position) WHERE status = 'UNCONSUMED';
CREATE INDEX IF NOT EXISTS saga_message_log_consumed
    ON saga_message_log (consumed_at) WHERE status = 'CONSUMED';
CREATE INDEX IF NOT EXISTS saga_message_log_source
    ON saga_message_log (source_saga_type, source_saga_id);

CREATE TABLE IF NOT EXISTS saga_message_log_consumer (
    consumer_id   VARCHAR(64)  PRIMARY KEY,
    host          VARCHAR(255) NOT NULL,
    started_at    TIMESTAMPTZ  NOT NULL,
    last_seen_at  TIMESTAMPTZ  NOT NULL
);
