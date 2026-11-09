-- The schema.sql of specs/003 and specs/004 (before specs/005-step-retry), kept to test schema-upgrade-005.sql.
-- Applied by the host application; this module ships no migration tool.

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
    CONSTRAINT outcome_iff_completed
        CHECK ((status = 'COMPLETED') = (outcome IS NOT NULL)),
    CONSTRAINT failure_iff_failed
        CHECK ((status = 'FAILED') = (failure_step_name IS NOT NULL))
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
    occurred_at         TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (saga_id, sequence_number)
);
