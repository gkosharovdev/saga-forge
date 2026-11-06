# Phase 1 Data Model: PostgreSQL Saga Persistence

**Feature**: [spec.md](./spec.md) | **Research**: [research.md](./research.md)

Two groups of entities: (A) existing `saga-forge-core` types this feature persists as-is (no shape
change — spec.md Assumptions), and (B) two new `saga-forge-core` types FR-007 requires. Table mappings
are the Postgres-specific projection of (A)/(B); DDL lives in
[contracts/postgres-schema.md](./contracts/postgres-schema.md).

Every `JSONB` column (`state`, `steps`, `values`, `touched_aggregates`) goes through the package-private
`JsonCodec`, a thin wrapper over one shared Jackson 3 `JsonMapper` (research.md §9). `Map<String, Object>`
values (`state`, `values`) are limited to `String`, `Boolean`, `Integer`, `Long`, finite `Double`, and
nested `String`-keyed maps and lists; anything else is rejected on write, and a `Long` in the `int` range
reads back as `Integer` (`.specify/memory/memory.md` G-003-07).

## A. Existing core types persisted (unchanged shape)

### `SagaInstance` (`core.spi`)

| Field | Type | Persisted as |
|---|---|---|
| `sagaId` | `String` | `saga_instance.saga_id` (PK) |
| `sagaType` | `String` | `saga_instance.saga_type` |
| `state` | `Map<String, Object>` | `saga_instance.state` (`JSONB`) |
| `associations` | `Map<String, String>` | one row per entry in `saga_association` |
| `status` | `SagaStatus` enum | `saga_instance.status` (text) |
| `createdAt` | `Instant` | `saga_instance.created_at` (`timestamptz`) |
| `lastModifiedAt` | `Instant` | `saga_instance.last_modified_at` (`timestamptz`) |
| `steps` | `Map<String, StepRecord>` | `saga_instance.steps` (`JSONB`) |
| `outcome` | `String`, nullable | `saga_instance.outcome` (nullable text) |
| `failure` | `SagaFailure`, nullable | flattened into `saga_instance.failure_*` columns (nullable) |

Invariants enforced by the record's own compact constructor (outcome ⟺ COMPLETED, failure ⟺ FAILED,
non-null `steps` map with no null keys/values) are **not re-validated** by the storage module on read —
`findById`/`findByAssociation` reconstruct a `SagaInstance` from a row that was only ever written by
`save(SagaInstance)` with an already-valid instance, so reconstruction cannot produce an invalid one
(FR-011). `save()` does re-derive the invariant implicitly: it never writes a row whose `outcome`/`failure`
disagrees with `status`, because the Java value it serializes already satisfies the constructor.

### `StepRecord` (`core.spi`), nested inside `SagaInstance.steps`

| Field | Type | Persisted as |
|---|---|---|
| `status` | `StepStatus` enum (`NOT_RUN`/`COMPLETED`) | JSON field `status` inside each entry of `steps` |
| `touchedAggregates` | `List<AggregateReference>` | JSON array field `touchedAggregates`, each element `{"aggregateType": ..., "aggregateId": ...}` |

One JSON object per map entry, keyed by step name, e.g.:
```json
{"processPayment": {"status": "COMPLETED", "touchedAggregates": [{"aggregateType": "...Order", "aggregateId": "42"}]}}
```

### `SagaFailure` (`core.spi`), nested inside `SagaInstance.failure`

| Field | Type | Persisted as |
|---|---|---|
| `stepName` | `String` | `saga_instance.failure_step_name` |
| `causeType` | `String` | `saga_instance.failure_cause_type` |
| `message` | `String`, nullable | `saga_instance.failure_message` |
| `failedAt` | `Instant` | `saga_instance.failure_failed_at` |

All four columns are `NULL` together when `status != FAILED`; `save()` never writes a partial set (same
compact-constructor guarantee as above).

### `SagaContextRecord` (`core.spi`)

| Field | Type | Persisted as |
|---|---|---|
| `sagaId` | `String` | `saga_context.saga_id` (PK, FK → `saga_instance.saga_id` `ON DELETE CASCADE`) |
| `values` | `Map<String, Object>` | `saga_context.values` (`JSONB`) |
| `lastModifiedAt` | `Instant` | `saga_context.last_modified_at` |

Written by `PostgresSagaContextStore` using its own `Connection`, never inside the transaction
`PostgresStorageProvider.save()` opens (research.md §6, FR-005).

**Write order** (research.md §8): because of the FK, a `SagaContextRecord` may only be saved once its
`SagaInstance` row exists and is committed. The caller (the future engine's unit of work) persists the
instance with `StorageProvider.save` before the first `SagaContextStore.save` for that `sagaId`. A context
save for a `sagaId` with no instance row fails with an FK violation, surfaced as `UncheckedSqlException`;
that is the expected failure mode.

## B. New `saga-forge-core` vocabulary (FR-007)

Both types live in a new `core.spi` (or a sibling package, e.g. `core.spi.history` — final package name is
an implementation choice at coding time, not a design decision this plan fixes) package, next to the other
SPI types. Neither is referenced by any existing interface's method signature — nothing in `core.spi` gains
a new method (research.md §2).

### `SagaLifecycleEventKind` (new enum)

| Value | Meaning |
|---|---|
| `CREATED` | The instance was first persisted (no prior row existed), whatever its first `status` (`.specify/memory/memory.md` G-003-01). |
| `STEP_COMPLETED` | A step transitioned `NOT_RUN` → `COMPLETED` between the previous persisted snapshot and this one. |
| `COMPLETED` | `status` transitioned to `SagaStatus.COMPLETED` (an `@EndSaga` outcome was reached). |
| `FAILED` | `status` transitioned to `SagaStatus.FAILED`. |

### `SagaLifecycleEntry` (new record)

| Field | Type | Always present? | Notes |
|---|---|---|---|
| `sagaId` | `String` | yes | matches the owning `SagaInstance.sagaId()` |
| `sequenceNumber` | `long` | yes | monotonically increasing per `sagaId`, assigned at derivation time (research.md §5) |
| `kind` | `SagaLifecycleEventKind` | yes | one of the four values above |
| `stepName` | `String`, nullable | only for `STEP_COMPLETED` | the step that completed |
| `touchedAggregates` | `List<AggregateReference>` | only for `STEP_COMPLETED` (else empty) | copied from the step's `StepRecord.touchedAggregates()` at the moment it completed |
| `outcomeStep` | `String`, nullable | only for `COMPLETED` | copied from `SagaInstance.outcome()` |
| `failure` | `SagaFailure`, nullable | only for `FAILED` | copied from `SagaInstance.failure()` |
| `occurredAt` | `Instant` | yes | `SagaInstance.lastModifiedAt()` of the snapshot that produced this entry (for `CREATED`, its `createdAt()`) |

Validation mirrors the existing style (`IllegalArgumentException` on null required fields; `kind`-dependent
fields other than the one `kind` implies must be null/empty — same "present iff" pattern as
`SagaInstance.outcome`/`failure`). Storage as `saga_lifecycle_history` rows: see
[contracts/saga-lifecycle-entry.md](./contracts/saga-lifecycle-entry.md) for the full contract and
[contracts/postgres-schema.md](./contracts/postgres-schema.md) for the DDL.

## Derivation algorithm (`PostgresStorageProvider.save(incoming)`)

Runs entirely inside the one transaction from research.md §1, after the row lock from research.md §3:

1. Begin transaction; `SELECT ... FOR UPDATE` the `saga_instance` row for `incoming.sagaId()` (may find none).
2. If no row existed: this is a `CREATED` entry (`sequenceNumber = 0`); skip steps 3–4; go to step 5.
3. If a row existed, deserialize it into a `previous` `SagaInstance` and validate `incoming` is a forward
   transition from it (research.md §3): every `previous` step that is `COMPLETED` must still be `COMPLETED`
   in `incoming` with an equal `touchedAggregates`; if `previous.status()` is `COMPLETED` or `FAILED`,
   `incoming.status()` must be unchanged. Violation → throw `ConcurrentSagaUpdateException`, roll back, return.
4. Diff `previous.steps()` against `incoming.steps()`: for each step name whose status went
   `NOT_RUN → COMPLETED`, build one `STEP_COMPLETED` entry. Sort these by step name ascending (the
   documented tiebreak, research.md §5) and assign them the next consecutive `sequenceNumber`s.
5. If `incoming.status()` differs from `previous.status()` (or there was no previous row and
   `incoming.status()` is already terminal): append one more entry — `COMPLETED` (with `outcomeStep`) or
   `FAILED` (with `failure`) — with the next `sequenceNumber`. A `CREATED` entry from step 2 is always first
   (`sequenceNumber = 0`) when there was no previous row.
6. Upsert the `saga_instance` row, replace the `saga_association` rows (delete-then-insert for
   `incoming.sagaId()`) to match `incoming.associations()`, insert all new `saga_lifecycle_history` rows from
   steps 2–5, commit.

No entry is ever produced for a step that stayed `NOT_RUN`, or for a `save()` call whose `incoming` is
identical to `previous` in every diffed dimension (idempotent re-save produces zero new entries) — directly
satisfying FR-009 and User Story 3's Acceptance Scenario 2.

## State / sequencing notes

- `sequence_number` is assigned by the derivation algorithm, not a database identity/serial column, so that
  retrying a failed transaction never leaves a gap and two different `sagaId`s never need to coordinate a
  shared counter.
- Every table FK's `ON DELETE CASCADE` means `StorageProvider.delete(sagaId)` (FR-004) removes
  `saga_instance`, `saga_association`, `saga_context`, and `saga_lifecycle_history` rows for that `sagaId` in
  one statement's cascade — satisfying spec.md's orphan-cleanup Assumption without `PostgresStorageProvider`
  needing to know about `saga_context`/`saga_lifecycle_history` at all.
