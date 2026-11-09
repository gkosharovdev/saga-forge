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
    CONSTRAINT outcome_iff_completed
        CHECK ((status = 'COMPLETED') = (outcome IS NOT NULL)),
    CONSTRAINT failure_iff_failed
        CHECK ((status = 'FAILED') = (failure_step_name IS NOT NULL)
            AND (status = 'FAILED') = (failure_classification IS NOT NULL)
            AND (status = 'FAILED') = (failure_attempts IS NOT NULL)),
    CONSTRAINT pending_retry_only_while_active
        CHECK (pending_retry IS NULL OR status IN ('CREATED', 'RUNNING')),
    CONSTRAINT trigger_iff_compensation
        CHECK ((status IN ('COMPENSATING', 'COMPENSATED', 'REQUIRES_INTERVENTION'))
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
