# Data Model: Saga Compensation

**Feature**: `006-saga-compensation` | **Research**: [research.md](./research.md)

New and changed types. "Core" means `saga-forge-core`, which has zero dependencies. "Engine" means package-private in
`saga-forge-engine`. Existing constructors stay as compatibility overloads that default the new components, as 005
did.

## New core types

### `core.annotation.StartCompensation` (annotation)

| Property | Value |
|---|---|
| Target | `METHOD`, `RUNTIME` retention, no attributes |
| Placement | must co-occur with `@SagaStep`; never with `@StartSaga` or `@EndSaga` (R20, Property 30) |
| Effect | the method's name is the failure outcome recorded in the trigger (research §1) |

### `core.compensation.CompensationTrigger` (record)

| Field | Type | Rule |
|---|---|---|
| `kind` | `CompensationTriggerKind` | non-null |
| `stepName` | `String` | non-blank: the `@StartCompensation` step (`FAILURE_OUTCOME`) or the failing step |
| `eventType` | `String` | fully qualified event class name if and only if `kind == FAILURE_OUTCOME` |
| `failure` | `SagaFailure` | non-null if and only if `kind != FAILURE_OUTCOME`; classification `FINAL` for `FINAL_FAILURE`, `EXHAUSTED` for `RETRIES_EXHAUSTED` |
| `at` | `Instant` | non-null; when compensation was started |

Factories: `failureOutcome(stepName, eventType, at)`, `failed(SagaFailure, at)`. The factory derives `kind` from
`failure.classification()` and rejects `TRANSIENT` and `UNCLASSIFIED`.

### `core.compensation.CompensationTriggerKind` (enum)

`FAILURE_OUTCOME`, `FINAL_FAILURE`, `RETRIES_EXHAUSTED`.

### `core.spi.CompensationStatus` (enum)

`NOT_ATTEMPTED`, `COMPENSATED`, `NOTHING_TO_COMPENSATE`, `FAILED`.

### `core.spi.StepCompensation` (record)

| Field | Type | Rule |
|---|---|---|
| `status` | `CompensationStatus` | non-null |
| `at` | `Instant` | `null` if and only if `NOT_ATTEMPTED` |
| `failure` | `SagaFailure` | non-null if and only if `FAILED`; classification `FINAL`, `UNCLASSIFIED`, or `EXHAUSTED` for a transient cause after its single attempt (never `TRANSIENT`) |

Factories: `notAttempted()`, `nothingToCompensate(at)`, `compensated(at)`, `failed(failure)`. The last uses
`failure.failedAt()` as `at`.

## Changed core types

### `SagaStatus` (enum)

Gains the terminal values `COMPENSATED` and `REQUIRES_INTERVENTION`. `COMPENSATING`, which already exists, is used for
the first time.

### `HandlingOutcome` (enum)

Gains `COMPENSATED` and `REQUIRES_INTERVENTION`. `SagaHandlingResult` keeps its rules: no `outcomeStep` and no
`failure` for either value, and `unitOfWorkId` is that of the last unit of work run.

### `StepRecord` (record)

| Field | Change | Rule |
|---|---|---|
| `completionSequence` | new `int` | `0` unless `COMPLETED`; then `>= 1`. The engine assigns distinct values within an instance; not checked by the record, because the compatibility constructors default to 1 |
| `compensation` | new `StepCompensation` | `null` unless `COMPLETED` |

The existing constructors and factories default to `completionSequence = 1` when completed, else 0, and to
`compensation = null`. Wither methods: `withCompletionSequence(int)`, `withCompensation(StepCompensation)`.

### `SagaInstance` (record)

| Field | Change | Rule |
|---|---|---|
| `compensationTrigger` | new `CompensationTrigger`, the last component | non-null if and only if status is `COMPENSATING`, `COMPENSATED` or `REQUIRES_INTERVENTION` |

Invariants it gains:

| Status | Invariant |
|---|---|
| `CREATED`, `RUNNING`, `COMPLETED`, `FAILED` | no step has a `compensation` |
| `COMPENSATED` | no step has `NOT_ATTEMPTED` or `FAILED` |
| `REQUIRES_INTERVENTION` | exactly one step has `FAILED` |

`pendingRetry` stays limited to `CREATED`/`RUNNING`. Wither: `withCompensationTrigger(CompensationTrigger)`.

### `SagaLifecycleEventKind` / `SagaLifecycleEntry`

| New kind | `stepName` | `failure` | `trigger` |
|---|---|---|---|
| `COMPENSATION_STARTED` | `null` | `null` | non-null |
| `STEP_COMPENSATED` | non-null | `null` | `null` |
| `COMPENSATION_FAILED` | non-null | non-null | `null` |
| `COMPENSATED` | `null` | `null` | `null` |
| `REQUIRES_INTERVENTION` | `null` | `null` | `null` |

`SagaLifecycleEntry` gains `trigger` as its last component, non-null if and only if the kind is
`COMPENSATION_STARTED`. The rules for `stepName` and `failure` are extended accordingly.

### `@SagaCompensation` (annotation)

Unchanged attributes. A method annotated with it gains rule R21:
- returns `void`;
- declares no parameter, or one parameter of type `CompensationTrigger`;
- is not a `@SagaStep`.

Rule R22 adds at most one compensation per step, and none for a `@StartCompensation` step.

## Engine types

| Type | Role |
|---|---|
| `SagaModel.Step.startsCompensation()` | the step carries `@StartCompensation` |
| `SagaModel.Compensation` | `(method, forStep, takesTrigger)`; `SagaModel.compensationFor(stepName)` returns an `Optional` |
| `SagaModel.run(Compensation, saga, trigger)` | invokes the method reflectively, unwrapping as steps do |
| `EngineSagaLifecycle.startCompensation(sagaId, trigger, compensable)` | enters `COMPENSATING`, sets the trigger and per-step statuses, adds a `COMPENSATION_STARTED` entry; ends `COMPENSATED` at once if nothing is `NOT_ATTEMPTED` |
| `EngineSagaLifecycle.recordCompensated(sagaId, step)` | `NOT_ATTEMPTED → COMPENSATED` with a `STEP_COMPENSATED` entry; ends `COMPENSATED` (entry) after the last one |
| `EngineSagaLifecycle.recordCompensationFailed(sagaId, step, failure)` | `NOT_ATTEMPTED → FAILED`, status `REQUIRES_INTERVENTION`, with `COMPENSATION_FAILED` and `REQUIRES_INTERVENTION` entries |
| `SagaUnitOfWork` | handles a `@StartCompensation` step like an end step, by calling `startCompensation`; returns `AttemptResult.Compensating` for an instance found `COMPENSATING` (checked before the duplicate check); `Committed` carries `compensationStarted` |
| `CompensationStartWrite` | own transaction, used for `FINAL`/`EXHAUSTED`: `COMPENSATING` with trigger, or `FAILED` if no step completed |
| `CompensationUnitOfWork` | one compensation in its own transaction (contracts/compensation-run.md) |
| `CompensationFailedWrite` | own transaction: step `FAILED`, instance `REQUIRES_INTERVENTION` |
| `CompensationRun` | runs the remaining compensations under the held lease; collects the handoff batches |

## State transitions

```text
RUNNING ──@StartCompensation step commits───────────────┐
RUNNING ──FINAL / EXHAUSTED failure, ≥1 step COMPLETED──┤ (CompensationStartWrite)
CREATED/RUNNING ──FINAL / EXHAUSTED, no step COMPLETED──┼──> FAILED (as 005)
                                                        v
                                                  COMPENSATING ──every selected step COMPENSATED or
                                                        │         NOTHING_TO_COMPENSATE──> COMPENSATED
                                                        └──a compensation fails──> REQUIRES_INTERVENTION

per selected step:  null ──start──> NOT_ATTEMPTED ──commit──> COMPENSATED
                                  │                └─failure──> FAILED
                                  └──start──> NOTHING_TO_COMPENSATE (no @SagaCompensation)
```

Selected steps are every `COMPLETED` step except the `@StartCompensation` step that triggered compensation. They are
run in decreasing `completionSequence` order (research §5).

## Validation summary

| Rule | Where | Spec |
|---|---|---|
| R20 / Property 30: `@StartCompensation` placement | scanner `validate`/`validateAll`, engine startup | FR-001a |
| R21 / Property 31: compensation method shape | scanner | FR-009, FR-010 |
| R22 / Property 32: one compensation per step, none for a trigger step | scanner | FR-008 |
| Property 33: selection and reverse order | engine (`CompensationSelectionPropertyTest`) | FR-005, SC-002 |
| Property 34: each compensation at most once, none skipped, across crashes | engine (`CompensationResumePropertyTest`) | FR-012, SC-003 |
| Trigger present only while compensating or compensated | `SagaInstance`, Postgres `CHECK` | FR-017 |
| Sequence, compensation status and trigger only move forward | Postgres `ForwardTransition` | FR-004, FR-012 |
