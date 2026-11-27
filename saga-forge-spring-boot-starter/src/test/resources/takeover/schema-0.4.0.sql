-- Saga Forge PostgreSQL schema (specs/003-postgres-saga-persistence/contracts/postgres-schema.md).
-- Applied by the host application; this module ships no migration tool. A database created from the schema before
-- specs/005-step-retry is brought up to date with schema-upgrade-005.sql, one created before
-- specs/006-saga-compensation with schema-upgrade-006.sql.

CREATE TABLE saga_instance (
    saga_id             TEXT PRIMARY KEY,
    saga_type           TEXT NOT NULL,
    state               JSONB NOT NULL,
    status              TEXT NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL,
    last_modified_at    TIMESTAMPTZ NOT NULL,
    steps               JSONB NOT NULL,
    outcome             TEXT,
    failure_step_name   TEXT,
    failure_cause_type  TEXT,
    failure_message     TEXT,
    failure_failed_at   TIMESTAMPTZ,
    failure_classification TEXT,
    failure_attempts    INT,
    pending_retry       JSONB,
    compensation_trigger JSONB,
    -- Open deadlines by name (specs/017-saga-deadlines FR-014); empty unless CREATED or RUNNING.
    open_deadlines      JSONB NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT open_deadlines_only_while_active
        CHECK (open_deadlines = '{}'::jsonb OR status IN ('CREATED', 'RUNNING')),
    CONSTRAINT outcome_iff_completed
        CHECK ((status = 'COMPLETED') = (outcome IS NOT NULL)),
    CONSTRAINT failure_iff_failed
        CHECK ((status = 'FAILED') = (failure_step_name IS NOT NULL)
            AND (status = 'FAILED') = (failure_classification IS NOT NULL)
            AND (status = 'FAILED') = (failure_attempts IS NOT NULL)),
    CONSTRAINT pending_retry_only_while_active
        CHECK (pending_retry IS NULL OR status IN ('CREATED', 'RUNNING')),
    CONSTRAINT trigger_iff_compensation
        CHECK ((status IN ('COMPENSATING', 'COMPENSATED', 'COMPENSATION_FAILED'))
            = (compensation_trigger IS NOT NULL))
);

CREATE TABLE saga_association (
    saga_id                TEXT NOT NULL REFERENCES saga_instance (saga_id) ON DELETE CASCADE,
    association_property   TEXT NOT NULL,
    association_value      TEXT NOT NULL,
    PRIMARY KEY (saga_id, association_property)
);

CREATE INDEX idx_saga_association_lookup
    ON saga_association (association_property, association_value);

CREATE TABLE saga_context (
    saga_id             TEXT PRIMARY KEY REFERENCES saga_instance (saga_id) ON DELETE CASCADE,
    values              JSONB NOT NULL,
    last_modified_at    TIMESTAMPTZ NOT NULL
);

CREATE TABLE saga_lifecycle_history (
    saga_id             TEXT NOT NULL REFERENCES saga_instance (saga_id) ON DELETE CASCADE,
    sequence_number     BIGINT NOT NULL,
    kind                TEXT NOT NULL,
    step_name           TEXT,
    touched_aggregates  JSONB NOT NULL DEFAULT '[]',
    outcome_step        TEXT,
    failure_step_name   TEXT,
    failure_cause_type  TEXT,
    failure_message     TEXT,
    failure_failed_at   TIMESTAMPTZ,
    failure_classification TEXT,
    failure_attempts    INT,
    occurred_at         TIMESTAMPTZ NOT NULL,
    compensation_trigger JSONB,
    PRIMARY KEY (saga_id, sequence_number)
);

-- Message store of the in-memory transport (specs/007-in-memory-transport data-model.md): a transactional outbox.
CREATE TABLE IF NOT EXISTS saga_message_store (
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
    status            VARCHAR(16)  NOT NULL CHECK (status IN ('UNCONSUMED', 'CONSUMED', 'SET_ASIDE', 'CANCELLED')),
    attempts          INTEGER      NOT NULL DEFAULT 0,
    next_attempt_at   TIMESTAMPTZ  NOT NULL,
    last_failure      JSONB,
    leased_by        VARCHAR(64),
    leased_until     TIMESTAMPTZ,
    consumed_at       TIMESTAMPTZ,
    set_aside_reason  VARCHAR(4096),
    external_ref      VARCHAR(255),
    -- Propagated trace context (specs/010-otel-observability contracts/message-context.md); NULL when none.
    trace_context     JSONB,
    -- Not leasable before this time; NULL when due at once (specs/017-saga-deadlines research R1). Copied into
    -- next_attempt_at on append, so the due index serves it; kept for operator queries.
    not_before        TIMESTAMPTZ,
    CONSTRAINT command_id_iff_command CHECK ((kind = 'COMMAND') = (command_id IS NOT NULL)),
    CONSTRAINT lease_together CHECK ((leased_by IS NULL) = (leased_until IS NULL)),
    CONSTRAINT consumed_at_iff_consumed CHECK ((status IN ('CONSUMED', 'CANCELLED')) = (consumed_at IS NOT NULL)),
    CONSTRAINT reason_iff_set_aside CHECK ((status = 'SET_ASIDE') = (set_aside_reason IS NOT NULL))
);

CREATE INDEX IF NOT EXISTS saga_message_store_head
    ON saga_message_store (ordering_key, position) WHERE status = 'UNCONSUMED';
CREATE INDEX IF NOT EXISTS saga_message_store_due
    ON saga_message_store (next_attempt_at, position) WHERE status = 'UNCONSUMED';
CREATE INDEX IF NOT EXISTS saga_message_store_consumed
    ON saga_message_store (consumed_at) WHERE status IN ('CONSUMED', 'CANCELLED');
CREATE INDEX IF NOT EXISTS saga_message_store_source
    ON saga_message_store (source_saga_type, source_saga_id);

-- The leadership of the store (specs/013-active-standby-relay data-model.md): one row, never deleted, so the
-- fencing token only grows. Only the relay holding an unexpired lease with the current token leases messages.
CREATE TABLE IF NOT EXISTS saga_message_store_leader (
    id             SMALLINT     PRIMARY KEY CHECK (id = 1),
    holder_id      VARCHAR(64)  NOT NULL,
    holder_host    VARCHAR(255) NOT NULL,
    fencing_token  BIGINT       NOT NULL CHECK (fencing_token >= 1),
    acquired_at    TIMESTAMPTZ  NOT NULL,
    expires_at     TIMESTAMPTZ  NOT NULL,
    CONSTRAINT expires_after_acquired CHECK (expires_at >= acquired_at)
);
