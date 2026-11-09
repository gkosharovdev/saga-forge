# Contract: `SagaLifecycle`

**Package**: `io.github.gkosharovdev.sagaforge.core.lifecycle` | **Kind**: interface, no implementation
shipped in this feature | **Consumer**: the saga engine/runtime (future module) only — never step-author
code (FR-008, spec Assumptions).

## Purpose

Governs the whole-instance process-orchestration transitions a saga instance goes through: creation,
step advancement, and completion (FR-005). Distinct from `SagaContext`, which governs per-step business
state (FR-007).

## Operations

### `SagaInstance start(Class<?> sagaType, String associationProperty, String associationValue)`

- **Pre-conditions**: `sagaType`, `associationProperty`, `associationValue` are non-null. No currently
  active (non-terminal) instance of `sagaType` already correlates to `associationValue` under
  `associationProperty`.
- **Effect**: creates a new `SagaInstance` with a freshly generated `sagaId`, `status = CREATED`, empty
  `state`, and an `associations` entry for `associationProperty -> associationValue`. Returns it.
- **Failure**: throws `DuplicateSagaInstanceException` if the pre-condition on active instances is
  violated (FR-006). Throws `IllegalArgumentException` if any argument is `null`, consistent with the
  existing SPI's null-argument convention (`StorageProvider`, `EventDispatcher`).

### `SagaInstance advance(String sagaId)`

- **Pre-conditions**: an instance with `sagaId` exists and its `status` is not `COMPLETED` or `FAILED`.
- **Effect**: returns an updated `SagaInstance` reflecting progress to the next step; a `CREATED` instance
  transitions to `RUNNING` on its first advancement.
- **Failure**: throws `InvalidSagaTransitionException` if no such instance exists, or it is already in a
  terminal status (FR-006). Throws `IllegalArgumentException` if `sagaId` is `null`.

### `SagaInstance complete(String sagaId)`

- **Pre-conditions**: an instance with `sagaId` exists, is not already terminal, and its current step is
  marked `@EndSaga`.
- **Effect**: returns an updated `SagaInstance` with `status = COMPLETED`. No further `advance`/`complete`
  call for this `sagaId` succeeds afterward.
- **Failure**: throws `InvalidSagaTransitionException` if no such instance exists or it is already
  terminal (FR-006). Throws `IllegalArgumentException` if `sagaId` is `null`.

## Relationship to other SPI contracts

- **`EventDispatcher`**: resolves which instance an incoming event correlates to (routing). The engine,
  having resolved a target, calls into `SagaLifecycle` to perform the actual transition. `EventDispatcher`
  itself never calls `SagaLifecycle`.
- **`StorageProvider`**: not called by `SagaLifecycle` methods directly. The engine persists the
  `SagaInstance` returned by a `SagaLifecycle` operation via `StorageProvider.save(...)`. Implementations
  of `SagaLifecycle` (future engine module) are expected to call `StorageProvider` as part of fulfilling
  these operations durably — this contract only specifies the in-memory transition semantics.
- **`SagaContext`**: entirely separate consumer path (step-author code, not engine code). See
  `saga-context.md`.

## Independent test shape (per spec User Story 2)

Exercisable against a test double with no real engine, storage, or transport:
1. `start` with no prior instance → new instance, `status == CREATED`.
2. `advance` on that instance → reflects progress.
3. `complete` on an instance whose current step is `@EndSaga` → `status == COMPLETED`.
4. `advance` or `complete` again on the now-terminal instance → `InvalidSagaTransitionException`.

## 004 delta (2026-11-09, `specs/004-saga-engine`)

Source: `specs/004-saga-engine/contracts/contract-deltas.md`. Where this contract says "the future engine", read `saga-forge-engine`.

- "Relationship to other SPI contracts": `EventDispatcher` no longer resolves instances and is outbound only. The
  engine resolves them behind `EventReceiver`, through `StorageProvider.findByAssociation`; `registerSagaType` is
  called once per saga type at `start()`, before the first event is accepted (FR-036).
- The production implementation (`EngineSagaLifecycle`) applies transitions to the unit of work's working copy and
  persists them with three writes (creation, unit of work, `FAILED`); every transition except `advance` yields one
  lifecycle entry. `start` performs the duplicate check against storage and does not persist; the engine's creation
  write does.
- The engine only ever passes an `@EndSaga` step to `complete` (FR-015).
