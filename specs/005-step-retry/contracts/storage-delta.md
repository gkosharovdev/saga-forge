# Contract: Storage Delta

**Feature**: `005-step-retry` | Spec FR-015 to FR-019 | Research §9, §11, §12

## `StorageProvider` (core SPI)

No new operation. Implementations must persist and return, unchanged:

- `SagaInstance.pendingRetry` (nullable);
- `StepRecord.attempts` and `StepRecord.handledCommands` (order preserved);
- `SagaFailure.classification` and `SagaFailure.attempts`, on the instance and on lifecycle entries;
- lifecycle entries of kind `ATTEMPT_FAILED` (step name and failure), in the order given.

Rejecting as a concurrent update (`ForwardTransition` in Postgres): a completed step whose `attempts` or
`handledCommands` differ from the stored ones; `pendingRetry` on a terminal instance.

## Postgres (`saga-forge-storage-postgres`)

`schema.sql` (fresh installs) and new `schema-upgrade-005.sql` (existing 003/004 databases):

```sql
ALTER TABLE saga_instance
    ADD COLUMN failure_classification TEXT,
    ADD COLUMN failure_attempts       INT,
    ADD COLUMN pending_retry          JSONB;
ALTER TABLE saga_instance DROP CONSTRAINT failure_iff_failed;
UPDATE saga_instance SET failure_classification = 'UNCLASSIFIED', failure_attempts = 1
 WHERE status = 'FAILED';
ALTER TABLE saga_instance ADD CONSTRAINT failure_iff_failed CHECK (
    (status = 'FAILED') = (failure_step_name IS NOT NULL)
    AND (status = 'FAILED') = (failure_classification IS NOT NULL)
    AND (status = 'FAILED') = (failure_attempts IS NOT NULL));
ALTER TABLE saga_instance ADD CONSTRAINT pending_retry_only_while_active CHECK (
    pending_retry IS NULL OR status IN ('CREATED', 'RUNNING'));

ALTER TABLE saga_lifecycle_history
    ADD COLUMN failure_classification TEXT,
    ADD COLUMN failure_attempts       INT;
UPDATE saga_lifecycle_history SET failure_classification = 'UNCLASSIFIED', failure_attempts = 1
 WHERE kind = 'FAILED';
```

JSON shapes (Jackson, in `JsonCodec`):

```json
"steps": { "processPayment": { "status": "COMPLETED", "touchedAggregates": [...], "attempts": 2,
           "handledCommands": [ { "sagaId": "…", "stepName": "processPayment",
                                  "commandType": "…ReserveStockCommand", "ordinal": 1, "value": "…" } ] } }
"pending_retry": { "triggerStep": "processPayment", "attemptsStarted": 2,
                   "lastFailure": { "stepName": "…", "causeType": "…", "message": "…",
                                    "failedAt": "…", "classification": "TRANSIENT", "attempts": 1 } }
```

Readers default absent `attempts` to `1` for `COMPLETED` (`0` for `NOT_RUN`) and absent `handledCommands` to `[]`, so
rows written before 005 load unchanged.
