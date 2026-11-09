# Contract: Storage Delta

**Feature**: `006-saga-compensation` | Spec FR-017, FR-018, FR-020 | Research §6, §11, §12

## `StorageProvider` (core SPI)

No new method. `save(instance, entries)` persists the new `SagaInstance` and `StepRecord` components and the new
lifecycle kinds. The rules that `findById`, `findByIdAndLock` and `findByAssociation` already follow apply to the new
statuses. `findByAssociation` returns compensating and compensated instances like any other. The engine's
duplicate-start check (004) treats `COMPENSATING` as active and the two new statuses as ended.

Test doubles in the engine test-jar (`TransactionalInMemoryStorageProvider` and others) store the records as given.
They need no change beyond compiling.

## Postgres (`saga-forge-storage-postgres`)

### `saga_instance`

| Column | Type | Content |
|---|---|---|
| `compensation_trigger` | `JSONB NULL` | `{"kind", "stepName", "eventType", "failure": {stepName, causeType, message, failedAt, classification, attempts}, "at"}` |

New constraint, alongside the existing ones:

```sql
CONSTRAINT trigger_iff_compensation
    CHECK ((status IN ('COMPENSATING', 'COMPENSATED', 'REQUIRES_INTERVENTION'))
           = (compensation_trigger IS NOT NULL))
```

`outcome_iff_completed`, `failure_iff_failed` and `pending_retry_only_while_active` are unchanged.

### `steps` JSON, per step

```json
{"status": "COMPLETED", "touchedAggregates": [...], "attempts": 1, "handledCommands": [...],
 "sequence": 2,
 "compensation": {"status": "FAILED", "at": "...", "failure": {...}}}
```

On decode, a missing `sequence` defaults to `0` and a missing `compensation` to `null`. After the upgrade script has
run, a `COMPLETED` step with `sequence == 0` cannot occur. If one is found anyway, the decoder rejects the row with
`UncheckedSqlException` naming the step.

### `saga_lifecycle_history`

| Column | Type | Content |
|---|---|---|
| `compensation_trigger` | `JSONB NULL` | as on `saga_instance`; set only for `COMPENSATION_STARTED` |

The new kinds reuse `step_name` (for `STEP_COMPENSATED` and `COMPENSATION_FAILED`) and the `failure_*` columns (for
`COMPENSATION_FAILED`).

### `ForwardTransition` additions

These checks are rejected with `ConcurrentSagaUpdateException`, like the 003 and 005 ones:
- **`sequence`**: a completed step's `sequence` never changes.
- **Step `compensation`**: only these moves are allowed; any other change is rejected.
  - `null → NOT_ATTEMPTED | NOTHING_TO_COMPENSATE`
  - `NOT_ATTEMPTED → COMPENSATED | FAILED`
- **`compensation_trigger`**: once set, it never changes.
- **Status**: `COMPENSATING` only moves to `COMPENSATED` or `REQUIRES_INTERVENTION`. Those two are terminal, so their
  status never changes.

### `schema-upgrade-006.sql`

1. Adds `saga_instance.compensation_trigger`, the `trigger_iff_compensation` constraint and
   `saga_lifecycle_history.compensation_trigger`.
2. Backfills `sequence` into every `COMPLETED` step of `saga_instance.steps`. Steps are numbered per instance by the
   `sequence_number` of their `STEP_COMPLETED` history row. Completed steps without a history row follow those,
   ordered by step name (gap G-006-07).

The script is idempotent: it uses `IF NOT EXISTS` and leaves alone steps that already have a `sequence`. It is applied
by the host, like `schema-upgrade-005.sql`. `schema.sql` contains the 006 shape directly.
