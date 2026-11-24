# Data Model: Saga Outcome Events

**Feature**: `011-saga-outcome-events` | **Date**: 2026-11-20 | **Research**: [research.md](research.md)

All new public types live in `saga-forge-core` (JDK only). Nothing new is stored in its own table: outcome events
travel as messages (research §11). The status rename changes stored values (research §6).

## Outcome events (`core.outcome`)

### `SagaOutcome` (sealed interface)

Permits `SagaCompleted`, `SagaFailed`, `SagaCompensated`. Not `@DomainEvent`-annotated.

| Accessor | Type | Rule |
|---|---|---|
| `eventId()` | `String` | `sagaId + ":" + terminal status` (`COMPLETED`, `FAILED`, `COMPENSATION_FAILED`, `COMPENSATED`); non-blank |
| `sagaType()` | `String` | fully qualified `@Saga` class name; non-blank |
| `sagaId()` | `String` | non-blank |
| `associations()` | `Map<String, String>` | the instance's associations (property → value), unmodifiable copy; non-empty |
| `occurredAt()` | `Instant` | the terminal transition's time, equal to the instance's `lastModifiedAt`; non-null |

### `SagaCompleted` (record)

Common components plus:

| Component | Type | Rule |
|---|---|---|
| `outcomeStep` | `String` | the `@EndSaga` step that ended the instance; non-blank |

### `SagaFailed` (record)

Common components plus:

| Component | Type | Rule |
|---|---|---|
| `phase` | `FailurePhase` | `STEP` or `COMPENSATION`; non-null |
| `failedStep` | `String` | `STEP`: the failed step. `COMPENSATION`: the step whose compensation failed. Non-blank |
| `classification` | `FailureClassification` | `STEP`: `FINAL`, `EXHAUSTED` or `UNCLASSIFIED`. `COMPENSATION`: the compensation failure's classification (`FINAL`, `UNCLASSIFIED`, or `EXHAUSTED` for a transient cause after its single attempt; compensations are never retried, G-006-09). Non-null |
| `causeType` | `String` | fully qualified exception class name; non-blank |
| `attempts` | `int` | ≥ 1 |
| `compensationTrigger` | `OutcomeTrigger` | non-null if and only if `phase == COMPENSATION` |

### `SagaCompensated` (record)

Common components plus:

| Component | Type | Rule |
|---|---|---|
| `trigger` | `OutcomeTrigger` | what started the compensation; non-null |

### `OutcomeTrigger` (record)

A message-free summary of `core.compensation.CompensationTrigger`.

| Component | Type | Rule |
|---|---|---|
| `kind` | `CompensationTriggerKind` | `FAILURE_OUTCOME` (a `@StartCompensation` step) or the failure kind; non-null |
| `stepName` | `String` | the `@StartCompensation` step, or the step whose failure triggered compensation; non-blank |
| `classification` | `FailureClassification` | for a failure: `FINAL` or `EXHAUSTED`; `null` for `FAILURE_OUTCOME` |
| `causeType` | `String` | for a failure: the cause's class; `null` for `FAILURE_OUTCOME` |
| `attempts` | `Integer` | for a failure: ≥ 1; `null` for `FAILURE_OUTCOME` |
| `at` | `Instant` | when compensation started; non-null |

Factory: `OutcomeTrigger.of(CompensationTrigger)`.

### `FailurePhase` (enum)

`STEP`, `COMPENSATION`.

### Validation

Every record validates in its canonical constructor and throws `IllegalArgumentException` naming the component.
`SagaFailed` rejects a `compensationTrigger` that does not match its `phase`.

### Mapping from the instance

| Terminal transition (lifecycle method) | Event | Built from |
|---|---|---|
| `complete` → `COMPLETED` | `SagaCompleted` | `outcome` |
| `fail` → `FAILED` | `SagaFailed(STEP)` | `failure`: `stepName`, `classification`, `causeType`, `attempts` |
| `recordCompensationFailed` → `COMPENSATION_FAILED` | `SagaFailed(COMPENSATION)` | the compensation's `SagaFailure`, `compensationTrigger` |
| `endCompensatedIfDone` → `COMPENSATED` | `SagaCompensated` | `compensationTrigger` |

No message, context value or saga field is ever copied (FR-007).

## Renamed public values (`core.spi`)

| Type | Before | After |
|---|---|---|
| `SagaStatus` | `REQUIRES_INTERVENTION` | `COMPENSATION_FAILED` (terminal) |
| `HandlingOutcome` | `REQUIRES_INTERVENTION` | `COMPENSATION_FAILED` |
| `SagaLifecycleEventKind` | `COMPENSATION_FAILED`, then `REQUIRES_INTERVENTION` | `COMPENSATION_FAILED` only, the terminal entry |

State transitions are unchanged apart from the name: `COMPENSATING` → `COMPENSATED` | `COMPENSATION_FAILED`.

### Postgres

| Object | Change |
|---|---|
| `saga_instance.status` values | `REQUIRES_INTERVENTION` → `COMPENSATION_FAILED` (`schema-upgrade-011.sql`) |
| `trigger_iff_compensation` constraint | lists `COMPENSATION_FAILED` |
| `saga_lifecycle_history` rows of kind `REQUIRES_INTERVENTION` | deleted by the upgrade (redundant with the `COMPENSATION_FAILED` row) |

## Runtime-internal structure (package-private, no compatibility promise)

See [contracts/unit-of-work.md](contracts/unit-of-work.md).

| Type | Role |
|---|---|
| `UnitOfWork` (class, replaces the interface and seven classes) | one transaction; instance scope or standalone; recording, outgoing batch, outbox timing, conflict and rollback |
| `UnitOfWork.Work` (interface) | `admit`, `perform`, `recorded`; implemented by `RunSteps`, `RunCompensation`, `HandleCommand`, `RecordFailure`, `ClaimAttempt`, `StartCompensation`, `RecordCompensationFailure` |
| `UnitOfWork.Result<T, R>` (sealed) | `Committed`, `Skipped` (the typed reason of an `Admission.Skip`), `Failed`, `Conflicted` |
| `WorkContext` | what a work may use: lifecycle, saga object factory, running-method name, recorder hooks |
| `EngineSagaLifecycle.PendingWrite` | gains `Optional<SagaOutcome> outcome` |
| `EngineSettings` | gains `outcomeEvents` |
| `EventRouter` | `SagaOutcome` → empty report |

Removed: `SagaUnitOfWork`, `CompensationUnitOfWork`, `NonSagaUnitOfWork`, `FailedWrite`, `AttemptWrite`,
`CompensationStartWrite`, `CompensationFailedWrite` (their content moves into the works above).

## In-memory transport changes

| Type | Change |
|---|---|
| `OrderingKeys.ofEvent` | `SagaOutcome` → `"saga:" + sagaId` |

## Unchanged

`SagaInstance` (shape), `StepRecord`, `SagaFailure`, `SagaLifecycleEntry` (shape), `OutgoingMessages`, `Outbox`,
`NewMessage`, `StoredMessage`, every Postgres table's columns.
