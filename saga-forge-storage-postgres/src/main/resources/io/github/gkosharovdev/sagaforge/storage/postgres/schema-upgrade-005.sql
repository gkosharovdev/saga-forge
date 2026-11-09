-- Saga Forge PostgreSQL schema upgrade for specs/005-step-retry (contracts/storage-delta.md).
-- Applies to a database created from the schema.sql of specs/003 and specs/004. Applied by the host application;
-- this module ships no migration tool. Run it once, in one transaction, while no engine is running.

ALTER TABLE saga_instance
    ADD COLUMN failure_classification TEXT,
    ADD COLUMN failure_attempts       INT,
    ADD COLUMN pending_retry          JSONB;

UPDATE saga_instance SET failure_classification = 'UNCLASSIFIED', failure_attempts = 1
 WHERE status = 'FAILED';

ALTER TABLE saga_instance DROP CONSTRAINT failure_iff_failed;

ALTER TABLE saga_instance ADD CONSTRAINT failure_iff_failed
    CHECK ((status = 'FAILED') = (failure_step_name IS NOT NULL)
        AND (status = 'FAILED') = (failure_classification IS NOT NULL)
        AND (status = 'FAILED') = (failure_attempts IS NOT NULL));

ALTER TABLE saga_instance ADD CONSTRAINT pending_retry_only_while_active
    CHECK (pending_retry IS NULL OR status IN ('CREATED', 'RUNNING'));

ALTER TABLE saga_lifecycle_history
    ADD COLUMN failure_classification TEXT,
    ADD COLUMN failure_attempts       INT;

UPDATE saga_lifecycle_history SET failure_classification = 'UNCLASSIFIED', failure_attempts = 1
 WHERE kind = 'FAILED';
