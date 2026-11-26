# Data Model: Saga Operations

**Feature**: `specs/015-saga-operations` | **Research**: [research.md](research.md)

Every type is immutable, validates its components in the canonical constructor (EJ Item 49: `NullPointerException`
for a required null, `IllegalArgumentException` for an out-of-range value) and defensively copies collections
(`List.copyOf`, `Set.copyOf`, `Map.copyOf`; Item 50). Javadoc on records states the canonical constructor's exceptions in
a sentence, not with `@throws` (the release build's doclint rule).

## Core: `io.github.gkosharovdev.sagaforge.core.operations` (new package)

### `SagaOperations` (interface)

| Method | Returns | Notes |
|---|---|---|
| `find(String sagaId)` | `Optional<SagaInstance>` | Empty for an unknown id (FR-001). |
| `findByAssociation(String property, String value)` | `List<SagaInstance>` | Every saga type (FR-002). |
| `list(SagaInstanceQuery query)` | `SagaInstancePage` | FR-003. |
| `count(Set<String> sagaTypes)` | `InstanceCounts` | Empty set = every type (FR-004). |
| `history(String sagaId)` | `List<SagaLifecycleEntry>` | Occurrence order; empty for an unknown id (FR-007). |
| `context(String sagaId)` | `Map<String, Object>` | Context values as stored; empty map if none (D8, FR-006). |
| `resumeCompensation(String sagaId, OperatorRequest request)` | `RepairResult` | FR-012. |
| `compensate(String sagaId, OperatorRequest request)` | `RepairResult` | FR-013, FR-014. |
| `markResolved(String sagaId, OperatorRequest request)` | `RepairResult` | FR-015, FR-016. |
| `recover()` | `RecoveryReport` | One recovery sweep now (research R8). |

Thread-safe. Queries take no lock (FR-008). Repairs run synchronously and return after their compensations ended.

### `SagaInstanceQuery` (record)

| Component | Type | Rule |
|---|---|---|
| `statuses` | `Set<SagaStatus>` | Copied; empty = any. |
| `sagaTypes` | `Set<String>` | Copied; empty = any; no blank element. |
| `modifiedBefore` | `Instant` | Nullable; exclusive. |
| `modifiedAfter` | `Instant` | Nullable; exclusive; must be before `modifiedBefore` when both are set. |
| `limit` | `int` | 1..500. |
| `after` | `PageCursor` | Nullable: first page. |

Builder-style static factories: `SagaInstanceQuery.all()` (limit 50), plus withers `withStatuses(SagaStatus...)`,
`withSagaTypes(String...)`, `modifiedBefore(Instant)`, `modifiedAfter(Instant)`, `limit(int)` and `after(PageCursor)`.
That keeps a six-component record readable at call sites without a telescoping constructor (Item 2).

### `PageCursor` (record)

`(Instant lastModifiedAt, String sagaId)`, both required. `token()` gives a base64url `epochSecond.nano:sagaId` and
`PageCursor.fromToken(String)` parses it back (`IllegalArgumentException` if malformed). The HTTP endpoint uses only the
token.

### `SagaInstancePage` (record)

`(List<SagaInstance> instances, Optional<PageCursor> next)`. `next` is present if and only if more rows matched. (This is
a return value, not a stored record; `Optional` as a return-only component is accepted here, as in the transport's
`LeadershipStatus` precedent of documented nullability. Item 55.)

### `InstanceCounts` (record)

`(Map<SagaStatus, Long> byStatus, long compensationFailedUnresolved, long failedUnresolved)`. `byStatus` is an
`EnumMap` copy holding every status (0 when none). `get(SagaStatus)` is a convenience.

### `OperatorRequest` (record)

| Component | Type | Rule |
|---|---|---|
| `operator` | `String` | Non-blank, at most 200 characters. |
| `reason` | `String` | Non-blank, at most 2,000 characters. |

`OperatorRequest.recovery(String reason)` → operator `saga-forge-recovery` (constant `RECOVERY_OPERATOR`).

### `SagaResolution` (record)

`(String operator, String reason, Instant resolvedAt)`, all required. `of(OperatorRequest, Instant)`.

### `RepairResult` (sealed interface)

- `Applied(SagaInstance instance)`: the instance as it ended after the action (for resume and compensate, after the
  compensation run).
- `NotApplied(RepairRejection reason, SagaStatus actualStatus)`: `actualStatus` is null for `NOT_FOUND`.

`RepairRejection` enum: `NOT_FOUND`, `WRONG_STATUS`, `ALREADY_RESOLVED`. An exception is not used: a wrong status is an
expected outcome, not an exceptional one (Item 69; D4).

### `RecoveryReport` (record)

`(int compensationsResumed, int startsReported, int startsFailed, int errors)`, all ≥ 0. `none()`.

### `SagaStartAbandonedException` (final class, extends `RuntimeException`)

Its class name is the `causeType` of the `SagaFailure` recorded for an abandoned start (policy `FAIL`). It is never
thrown to callers.

## Core: changed types

| Type | Change |
|---|---|
| `spi.SagaInstance` | + `SagaResolution resolution` (nullable), + `int compensationRound` (≥ 0). Old constructors kept, defaulting to `null`/0. `withResolution`, `withCompensationRound`. |
| `spi.SagaLifecycleEntry` | + `OperatorRequest operator` (nullable). Old constructor kept. |
| `spi.SagaLifecycleEventKind` | + `COMPENSATION_RESUMED`, `RESOLVED`, `START_RECOVERED`. |
| `spi.StorageProvider` | + `find(SagaInstanceQuery)`, `countByStatus(Set<String>)`, `history(String)` (abstract; research R2). |
| `compensation.CompensationTriggerKind` | + `OPERATOR_REQUEST`. |
| `compensation.CompensationTrigger` | + `OperatorRequest operatorRequest`, present iff kind is `OPERATOR_REQUEST`. For that kind `failure` is optional (a `FAILED` instance's failure, or null) and `eventType`/`commandType`/`deadlineName` are null. Old constructors kept. Factory `byOperator(OperatorRequest, String stepName, SagaFailure failure, Instant at)`. |
| `outcome.OutcomeTrigger` | + `OperatorRequest operatorRequest`, same rule; mapped from the compensation trigger. |
| `outcome.SagaCompleted`/`SagaFailed`/`SagaCompensated` | Factories take the instance's `compensationRound`; `eventId` = `sagaId:STATUS` in round 0, else `sagaId:STATUS:r<round>` (research R11). |
| `telemetry.SagaTelemetrySettings` | + `Duration instanceCountInterval` (default 30 s, > 0). |

## Runtime: `io.github.gkosharovdev.sagaforge.runtime`

### `RecoverySettings` (public record)

| Component | Type | Default | Rule |
|---|---|---|---|
| `enabled` | `boolean` | `false` | |
| `interval` | `Duration` | 1 min | > 0 |
| `stallAge` | `Duration` | 5 min | > 0 |
| `batchSize` | `int` | 100 | 1..1,000 |
| `abandonmentAge` | `Duration` | 15 min | > 0 |
| `abandonedStartPolicy` | `AbandonedStartPolicy` | `REPORT` | |

`RecoverySettings.defaults()`, `enabledWithDefaults()`, and withers. `AbandonedStartPolicy` is a public enum: `REPORT`,
`FAIL`.

`SagaRuntime`: + `operations()`, + `stop()` (idempotent; a later `start()` starts the runtime again, G-015-08 closed), and
`Builder.recovery(RecoverySettings)`.

### Runtime internals (package-private, `runtime.internal`)

| Type | Role | Shape |
|---|---|---|
| `EngineSagaOperations` | Implements `SagaOperations`: queries delegate to `StorageProvider`/`SagaContextStore` in a read-only transaction; repairs take the shared `InstanceLocks`, run their work on `UnitOfWork.forInstance`, then `CompensationRun` where needed, and release the outbox after the lock. | Holds `Collaborators`, `InstanceLocks`, the saga models by type, `CompensationRun`. |
| `ResumeCompensation` | `Work<SagaInstance, RepairRejection>`. Admission (R4): an operator resume admits unresolved `COMPENSATION_FAILED`/`COMPENSATING`; `stalled(stallAge, now)` admits only `COMPENSATING` older than `stallAge`. | Two static factories, one admission predicate each. |
| `StartCompensation` | + `byOperator(trigger)` factory with its admission (R5). | Existing class. |
| `MarkResolved` | `Work<SagaInstance, RepairRejection>` (R6). | |
| `FailAbandonedStart` | `Work<SagaInstance, String>`. Admission: still `CREATED`, no step completed, older than the abandonment age. Writes `FAILED` (R8). | |
| `RecoverySweeper` | Runs one sweep (`sweep()` → `RecoveryReport`) and owns the optional scheduled thread (`start`, `stop`). | Holds `EngineSagaOperations`, `RecoverySettings`, `TimeAndIdentity`, `Diagnostics`, a `ScheduledExecutorService` supplier (injectable for tests). |
| `AbandonedStartReports` | The in-process "reported at" map (R9). | `ConcurrentHashMap<String, Instant>`. |
| `EngineSagaLifecycle` | + `resumeCompensation(sagaId, request)`, `resolve(sagaId, request)`, `failAbandonedStart(sagaId, failure)`, `recordStartRecovered(sagaId)`. | Existing. |
| `EventRouter` | Adopts a stranded `CREATED` instance on a duplicate start (R10). | Existing. |
| `SagaInstrumentation` | + operation span, counters, instances gauge with cached counts (R13). | Existing. |
| `Diagnostics` | + the log events of R13. | Existing. |

`EngineSagaOperations` is the single entry point used by both the public API and the sweeper. The sweeper never
re-implements a repair.

## Storage: Postgres

```sql
-- schema.sql (new databases) and schema-upgrade-015.sql (existing ones)
ALTER TABLE saga_instance ADD COLUMN resolution JSONB;                         -- {operator, reason, resolvedAt}
ALTER TABLE saga_instance ADD COLUMN compensation_round INT NOT NULL DEFAULT 0;
ALTER TABLE saga_lifecycle_history ADD COLUMN operator TEXT;
ALTER TABLE saga_lifecycle_history ADD COLUMN operator_reason TEXT;
CREATE INDEX IF NOT EXISTS saga_instance_status_modified
    ON saga_instance (status, last_modified_at, saga_id);
```

The trigger JSON (`compensation_trigger` columns) gains `operatorRequest` {operator, reason}. Absent in old rows, it
reads as null, so this needs no upgrade step (as with 012's `commandType`). `ForwardTransition` gains the rules of research
R12. `PostgresStorageProvider` implements `find` (keyset SQL, `saga_type = ANY(?)`, `status = ANY(?)`), `countByStatus`
(one `GROUP BY status, resolution IS NULL`) and `history` (`ORDER BY sequence_number`).

## State transitions (instance status)

```text
                 ┌──────────── adopt on redelivered start (FR-021) ──────────┐
CREATED ─────────┴──► RUNNING ──► COMPLETED                                   │
   │                    │  └──► (operator compensate) ──► COMPENSATING ◄─────┘ (normal flows unchanged)
   │                    │                                     │   ▲
   │ abandoned, FAIL    └─► FAILED ──(operator compensate)────┘   │ resume (round + 1)
   ▼                          │                                   │
 FAILED                       │ mark resolved            COMPENSATION_FAILED ── mark resolved ──► (same status, resolved)
                              ▼
                     (same status, resolved)
COMPENSATING ──► COMPENSATED | COMPENSATION_FAILED      (stalled COMPENSATING: resume, no status change)
```

A resolved instance accepts no repair (`ALREADY_RESOLVED`). `COMPLETED` and `COMPENSATED` accept none (`WRONG_STATUS`).

## Admission table

| Action | Admitted when | Otherwise |
|---|---|---|
| resume compensation (operator) | `COMPENSATION_FAILED` or `COMPENSATING`, unresolved | `NOT_FOUND`, `ALREADY_RESOLVED`, `WRONG_STATUS` |
| resume compensation (sweeper) | `COMPENSATING`, `lastModifiedAt` < now − stallAge | skipped silently (counted as nothing) |
| compensate | `RUNNING` or `FAILED`, unresolved | as above |
| mark resolved | `FAILED` or `COMPENSATION_FAILED`, unresolved | as above |
| fail abandoned start | `CREATED`, no completed step, `lastModifiedAt` < now − abandonmentAge | skipped |
| adopt stranded start | `CREATED`, no completed step (under the instance lock) | duplicate start, as today |

## Telemetry and logs

See research R13. Attribute names follow 010's `saga_forge.*` conventions. The gauge has one series per (status,
resolved) pair.
