# Contract: Postgres Schema (`saga-forge-storage-postgres`)

**Kind**: literal DDL, applied by the host application however it already manages schema (research.md §7 —
no migration-tool dependency is added by this feature). Spec: FR-001–FR-007, data-model.md. Every `JSONB`
column is written and read through `JsonCodec` (Jackson 3, research.md §9).

```sql
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
```

The DDL above is the shipped classpath resource
`saga-forge-storage-postgres/src/main/resources/io/github/gkosharovdev/sagaforge/storage/postgres/schema.sql`
statement for statement (the resource adds only a header comment). `saga_context.values` stays **unquoted**:
`VALUES` is a non-reserved (column-name) keyword in Postgres, accepted unquoted in the DDL, in `INSERT`/`SELECT`
column lists, and in `ON CONFLICT (saga_id) DO UPDATE SET values = EXCLUDED.values` (G-003-08, closed).

## Table-by-table notes

| Table | Row = | Key design choice |
|---|---|---|
| `saga_instance` | one `SagaInstance` | `state`/`steps` as `JSONB` (variable, per-saga-type shape); `SagaFailure` flattened into four nullable columns (fixed shape); two `CHECK` constraints mirror `SagaInstance`'s own compact-constructor invariants (defense in depth — `PostgresStorageProvider` never relies on the database to catch a violation Java already prevents, but a direct SQL write bypassing the module would still be caught) |
| `saga_association` | one `associations` map entry | composite PK **and** a dedicated lookup index (research.md §4) — the PK (`saga_id`, `association_property`) supports the per-instance delete/replace in the derivation algorithm's step 6; the separate `(association_property, association_value)` index supports `findByAssociation` (FR-003, SC-005), which never filters by `saga_id` |
| `saga_context` | one `SagaContextRecord` | PK doubles as the FK; `ON DELETE CASCADE` is what makes instance deletion also remove the context, per spec.md's orphan-cleanup Assumption. The FK also fixes the write order: instance row first (see "Write-order rule" below, research.md §8) |
| `saga_lifecycle_history` | one `SagaLifecycleEntry` | PK `(saga_id, sequence_number)` makes `sequence_number` unique per `saga_id` only (data-model.md), not globally; `touched_aggregates` defaults to an empty JSON array so only `STEP_COMPLETED` rows populate it |

## Failure modes

- A unique-constraint violation on `saga_instance`'s PK during an attempted insert cannot happen in normal
  operation: `save()`'s upsert (`INSERT ... ON CONFLICT (saga_id) DO UPDATE`) handles both "new instance"
  and "existing instance" without a separate existence check racing the row lock in research.md §3.
- Any `SQLException` from the driver propagates wrapped in
  `io.github.gkosharovdev.sagaforge.storage.postgres.UncheckedSqlException` (`extends RuntimeException`,
  constructor `(String message, SQLException cause)`), consistent with `StorageProvider`'s and
  `SagaContextStore`'s Javadoc ("storage failures propagate as unchecked exceptions"). The failed call's
  transaction is rolled back, so nothing is written.
- **Write-order rule** (research.md §8): a `saga_context` row may only be written once its `saga_instance`
  row exists and is committed. The caller persists the instance with `StorageProvider.save` before the first
  `SagaContextStore.save` for that `sagaId`. `PostgresSagaContextStore.save` for a `sagaId` with no instance
  row violates `saga_context`'s FK and throws `UncheckedSqlException`; that is the expected failure mode. The
  FK is kept deliberately (referential integrity and the delete cascade); dropping it was rejected.
- A `state`/`values` entry outside `JsonCodec`'s value set (research.md §9) fails with
  `IllegalArgumentException` naming the field, before any SQL runs.
- A forward-transition violation (research.md §3), or a lost concurrent first save of a new `sagaId`, throws
  `io.github.gkosharovdev.sagaforge.storage.postgres.ConcurrentSagaUpdateException`, unchecked, with a
  `sagaId()` accessor and a message naming the violated rule. It propagates unwrapped (not as
  `UncheckedSqlException`), is never referenced from `saga-forge-core`, and the call writes nothing (see
  [concurrency-control.md](./concurrency-control.md)).
- A `null` argument, or an instance with a `null` `sagaId`, `sagaType`, `status`, `createdAt` or
  `lastModifiedAt`, fails with `IllegalArgumentException` before any SQL runs (G-003-12).
- A `state`/`values` string or key containing U+0000 is not rejected by `JsonCodec`; `jsonb` refuses it and the
  call fails as `UncheckedSqlException` (G-003-09).

## 004 delta (2026-11-09, `specs/004-saga-engine`)

Source: `specs/004-saga-engine/contracts/contract-deltas.md`. Where this contract says "the future engine", read `saga-forge-engine`.

- DDL unchanged.
- `saga_lifecycle_history.sequence_number` is assigned by `save` from the hand-over order (`MAX + 1` onwards).
- New failure mode: in joined mode (a `PostgresStorageProvider` built with a `TransactionBoundary`, transaction
  active) a `SQLException` marks the transaction rollback-only before it is rethrown as `UncheckedSqlException`.
