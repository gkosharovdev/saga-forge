# Data Model: Step Retry and Idempotent Command Processing

**Feature**: `005-step-retry` | **Research**: [research.md](./research.md)

New and changed types. "Core" means `saga-forge-core` (zero dependencies); "engine" means package-private in
`saga-forge-engine` unless marked public.

## New core types

### `core.command.CommandId` (record)

| Field | Type | Rule |
|---|---|---|
| `sagaId` | `String` | non-blank; `null` only when `sagaScoped == false` |
| `stepName` | `String` | non-blank; `null` only when `sagaScoped == false` |
| `commandType` | `String` | fully qualified class name, non-blank |
| `ordinal` | `int` | `>= 1` when saga-scoped, `0` otherwise |
| `value` | `String` | saga-scoped: `UUID.nameUUIDFromBytes(sagaId \0 stepName \0 commandType \0 ordinal)`; otherwise a random UUID |
| `sagaScoped` | `boolean` | `true` for commands dispatched from a saga step |

Factories: `CommandId.of(sagaId, stepName, commandType, ordinal)`, `CommandId.unscoped(commandType)`. Equality by all
fields. `toString()` = `value`.

### `core.failure.TransientFailure`, `core.failure.FinalFailure` (marker interfaces)

No methods. Implemented by exception classes. A class implementing both is rejected at classification time as
`FINAL` (final wins; logged once).

### `core.failure.FailureClassification` (enum)

`TRANSIENT`, `FINAL`, `EXHAUSTED`, `UNCLASSIFIED`. Only `TRANSIENT` is retried. A persisted `FAILED` instance never
carries `TRANSIENT` (its last transient failure becomes `EXHAUSTED`).

### `core.annotation.RetryPolicy` (annotation, attribute value only)

See [contracts/retry-policy.md](./contracts/retry-policy.md) for attributes and validation (Property 29).

### `core.spi.PendingRetry` (record)

| Field | Type | Rule |
|---|---|---|
| `triggerStep` | `String` | non-blank; the step the triggering event runs |
| `attemptsStarted` | `int` | `>= 2` (written only after a failed attempt claims the next) |
| `lastFailure` | `SagaFailure` | non-null; classification `TRANSIENT`, `attempts < attemptsStarted` (`attemptsStarted - 1` after a failed attempt; lower once a redelivery claimed a further attempt after a crash) |

## Changed core types

### `SagaStep` (annotation)

Adds `RetryPolicy retry() default @RetryPolicy` (one attempt).

### `StepRecord` (record)

| Field | Change | Rule |
|---|---|---|
| `attempts` | new `int` | `0` when `NOT_RUN`; `>= 1` when `COMPLETED` |
| `handledCommands` | new `List<CommandId>` | empty unless `COMPLETED`; no duplicates; saga-scoped ids of this step only |

The 2-argument constructor and `completed(touchedAggregates)` stay, defaulting to `attempts = 1` and no commands.

### `SagaInstance` (record)

Adds `PendingRetry pendingRetry` (nullable) as the last component. Rule: non-null only when status is `CREATED` or
`RUNNING`.

### `SagaFailure` (record)

Adds `FailureClassification classification` (non-null) and `int attempts` (`>= 1`). The existing 4-argument
constructor and `of(stepName, cause, at)` default to `UNCLASSIFIED`, `1`; new factory
`of(stepName, cause, at, classification, attempts)`.

### `SagaLifecycleEventKind` / `SagaLifecycleEntry`

New kind `ATTEMPT_FAILED`. Rules: `stepName` non-null iff kind is `STEP_COMPLETED` or `ATTEMPT_FAILED`; `failure`
non-null iff kind is `FAILED` or `ATTEMPT_FAILED`.

### `HandlingOutcome` (enum)

New value `DUPLICATE`: the triggering step is already `COMPLETED` for the instance; nothing ran, nothing was
written. `SagaHandlingResult` rules unchanged (no `outcomeStep`, no `failure`, empty `handoffFailures`, no
`unitOfWorkId`).

## Engine types

| Type | Visibility | Role |
|---|---|---|
| `RetryPause` | public, `engine` | `void pause(Duration) throws InterruptedException`; default `Thread::sleep` |
| `SagaEngine.Builder.maxTotalRetryBackoff(Duration)` | public | default 30 s; startup check (research §7) |
| `SagaEngine.Builder.retryPause(RetryPause)` | public | test hook |
| `AttemptLoop` | internal | owns the instance lease; runs attempts; classifies; waits (research §8) |
| `FailureClassifier` | internal | cause-chain walk (research §5) |
| `Backoff` | internal | wait before attempt `k` and total wait of a policy |
| `AttemptWrite` | internal | own-transaction write of `PendingRetry` + `ATTEMPT_FAILED` entry (sibling of `FailedWrite`) |
| `CommandIds` | internal | per-step-run ordinal counter; bound-identity stack for the built-in resolver |

## State transitions

```text
              event for step S (attempt k)
                        │
         ┌──────────────┼──────────────────────────────┬───────────────────────┐
     S COMPLETED   commit                         failure TRANSIENT,       failure FINAL / UNCLASSIFIED,
         │         │                              k < maxAttempts(F)       or TRANSIENT with k = maxAttempts(F)
     DUPLICATE   steps COMPLETED (attempts = k),      │                          │
     (no write)  pendingRetry cleared if               AttemptWrite:             FailedWrite:
                 triggerStep = S                       pendingRetry(S, k+1),     status FAILED,
                                                       ATTEMPT_FAILED entry,     classification FINAL /
                                                       pause backoff(k+1),       UNCLASSIFIED / EXHAUSTED,
                                                       run attempt k+1           attempts = k
```

`F` is the step that failed in attempt `k` (may differ from `S` when the failure is in a later step of the same unit
of work). Within one handling call the loop carries `k` itself. On a later delivery (redelivery after a crash or an
interrupted wait), the claimed attempt counts as used: `k` starts at `pendingRetry.attemptsStarted + 1` if
`pendingRetry.triggerStep == S`, else at 1; if that `k` is `> maxAttempts` of the step named in
`pendingRetry.lastFailure`, the instance goes straight to `FAILED` `EXHAUSTED` with that failure's cause, without
running. Otherwise attempt `k` is claimed (`pendingRetry.attemptsStarted = k`, no history entry) before it runs, so a
crash during it cannot grant it again.

## Validation summary

| Rule | Where | Spec |
|---|---|---|
| Property 29: policy values valid | scanner `validate`/`validateAll` | FR-012 |
| Total backoff within `maxTotalRetryBackoff` | engine startup report | FR-014a |
| `pendingRetry` only on non-terminal instances | `SagaInstance`, Postgres check | FR-015 |
| Completed step's `attempts`/`handledCommands` immutable | Postgres `ForwardTransition` | FR-004, FR-017 |
