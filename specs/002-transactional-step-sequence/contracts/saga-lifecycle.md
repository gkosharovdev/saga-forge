# Contract delta: `SagaLifecycle` (extends feature 001)

**Base contract**: [`specs/001-core-lifecycle-context/contracts/saga-lifecycle.md`](../../001-core-lifecycle-c
ontext/contracts/saga-lifecycle.md)
| **Consumer**: the saga engine only (001 FR-008 still holds; steps never call it).

Only the differences from feature 001 are listed. All null arguments raise `IllegalArgumentException`.

### `SagaInstance start(Class<?> sagaType, String associationProperty, String associationValue)` *(unchanged)*

Additionally initializes `steps` with every `@SagaStep` method name of `sagaType` set to `NOT_RUN` with no
touched aggregates, and leaves
`outcome` and `failure` `null`.

### `SagaInstance recordStepCompleted(String sagaId, String stepName, List<AggregateReference> touchedAggregates)` *(new)*

- **Pre-conditions**: the instance exists, is not terminal, and `stepName` is one of its steps.
- **Effect**: sets `steps[stepName]` to `COMPLETED` with `touchedAggregates` (deduplicated, in order);
  status unchanged.
- **Failure**: `InvalidSagaTransitionException` (`notFound` or `terminal`); `IllegalArgumentException` for an
  unknown step.

### `SagaInstance advance(String sagaId)` *(meaning narrowed)*

Called when a unit of work commits at a wait point. `CREATED` or `RUNNING` → `RUNNING`. Rejections unchanged.

### `SagaInstance complete(String sagaId, String outcomeStep)` *(replaces `complete(String sagaId)`)*

- **Effect**: → `COMPLETED`, `outcome = outcomeStep`.
- **Pre-condition left to the caller**: `outcomeStep` is an `@EndSaga` step of the saga (same reasoning as
  001: `SagaLifecycle` holds no saga metadata).
- **Failure**: `InvalidSagaTransitionException` if missing or terminal.

### `SagaInstance fail(String sagaId, SagaFailure failure)` *(new)*

- **Effect**: `CREATED` or `RUNNING` → `FAILED`, `failure` recorded, `steps` unchanged.
- **Failure**: `InvalidSagaTransitionException` if missing or terminal.

## Terminal statuses

`COMPLETED` and `FAILED`, as in 001. `COMPENSATING` is never entered by this contract.
