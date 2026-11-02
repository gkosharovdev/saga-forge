# Phase 1 Data Model: Saga Lifecycle and Context Abstractions

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md) | **Research**: [research.md](./research.md)

All types below live in the new package `io.github.gkosharovdev.sagaforge.core.lifecycle`, inside the
existing `saga-forge-core` module. No existing type's signature changes.

## SagaLifecycle (interface)

**Represents**: the process-orchestration contract governing a saga instance's start, step advancement,
and completion transitions (FR-005, FR-006). Consumed by the saga engine/runtime (a future module) — never
called directly from inside a `@SagaStep` method body (spec Assumptions; FR-008).

**Operations**:

| Operation | Pre-conditions | Effect | Rejections |
|---|---|---|---|
| `start` | No instance of this saga type is currently active for the given association property/value pair | Creates and returns a new `SagaInstance` in status `CREATED` | `DuplicateSagaInstanceException` if an active (non-terminal) instance already correlates to the same saga type + association value |
| `advance` | The addressed instance exists and is not in a terminal status | Returns an updated `SagaInstance` whose recorded progress reflects the advancement; moves a `CREATED` instance to `RUNNING` on its first advancement | `InvalidSagaTransitionException` if the instance does not exist, or is already `COMPLETED`/`FAILED` |
| `complete` | The addressed instance exists, is not already terminal, and its current step is marked `@EndSaga` | Returns an updated `SagaInstance` in terminal status `COMPLETED`; no further `advance`/`complete` is accepted afterward | `InvalidSagaTransitionException` if the instance does not exist or is already terminal |

**Status transitions driven by this contract** (using the existing `SagaStatus` enum, unchanged):

```
CREATED --advance--> RUNNING --advance--> RUNNING --complete--> COMPLETED (terminal)
  \                                                                  ^
   \--- complete (single-step saga) ------------------------------/
```

`FAILED` and `COMPENSATING` are existing `SagaStatus` values this contract does not drive in this feature
— `FAILED` is reserved for the not-yet-built failure-categorization feature gap, and `COMPENSATING` for the
not-yet-built compensation-dispatch feature gap (`docs/architecture.md#feature-gaps`). Both remain terminal
or exempt from this contract's transitions as a forward-compatibility placeholder only; this spec does not
define how an instance enters either status.

## SagaContext (interface)

**Represents**: the step-execution contract exposing named, type-safe business-state read/write, scoped to
exactly one saga instance, available to a `@SagaStep` method during its execution (FR-001 through FR-004,
FR-002a/b/c, FR-010). Consumed only by step-author code (FR-008) — reached via the field-injection mechanism
decided in `research.md` §1.

**Operations**:

| Operation | Signature shape | Behavior |
|---|---|---|
| Read | `getState(name, type)` | Returns the value stored under `name` as `type`, or empty if never written (FR-004). If a value exists under `name` but is not assignable to `type`, the operation fails rather than returning a value (FR-003). |
| Write (whole-value) | `putState(name, value)` | Creates or replaces the entire value stored under `name` (FR-002). Subsequent `getState`/`updateStateAttribute` calls (by this or a later step of the same instance) observe it. |
| Write (field-level) | `updateStateAttribute(name, field, value)` | Mutates exactly one field of the structured value stored under `name`, leaving all other fields of that value unchanged (FR-002a). If no value exists under `name` yet, creates a new structured value containing only `field` (FR-002b, upsert). If a value exists under `name` but is not structured (a scalar), the operation fails without mutating it (FR-002c). |

**Scoping invariant** (FR-010): every `SagaContext` instance is bound to exactly one saga instance for its
entire lifetime; it exposes no operation that can read or write state belonging to a different instance,
and no two different saga instances' `SagaContext`s ever observe each other's writes.

**Validation rules**:
- `name` MUST NOT be `null` or blank for any operation.
- `field` MUST NOT be `null` or blank for `updateStateAttribute`.
- `type` MUST NOT be `null` for `getState`.
- `value` passed to `putState`/`updateStateAttribute` MUST NOT be `null` (`IllegalArgumentException`), so
  `getState` returning `Optional.empty()` unambiguously means "never written" (FR-004).

## Supporting Exception Types

| Type | Thrown by | Signals |
|---|---|---|
| `DuplicateSagaInstanceException` | `SagaLifecycle.start` | FR-006 — a start request collided with an already-active instance for the same saga type + correlation |
| `InvalidSagaTransitionException` | `SagaLifecycle.advance`, `SagaLifecycle.complete` | FR-006 — the addressed instance is missing or already in a terminal status |
| `SagaStateTypeMismatchException` | `SagaContext.getState` | FR-003 — a stored value exists under `name` but does not match the requested type; carries the name, expected type, and actual type for a precise message |
| `NonStructuredStateValueException` | `SagaContext.updateStateAttribute` | FR-002c — a scalar value already exists under `name`, so a field-level update cannot proceed |

All four are unchecked (`RuntimeException` subtypes), per `research.md` §4.

## Relationship to Existing Types (unchanged)

- **`SagaInstance`** *(existing record, unchanged)*: `SagaLifecycle` operations produce/consume
  `SagaInstance` values. `SagaContext`'s named values are defined to correspond 1:1 to entries of
  `SagaInstance.state : Map<String, Object>` — a scalar named value is a direct entry; a structured named
  value (and its independently-updatable fields) is a nested `Map<String, Object>` entry. No new field is
  added to `SagaInstance`.
- **`SagaStatus`** *(existing enum, unchanged)*: `SagaLifecycle`'s transitions are expressed purely in
  terms of this existing enum's values (see transitions diagram above).
- **`StorageProvider`** *(existing interface, unchanged)*: not called by `SagaContext` or `SagaLifecycle`
  directly in this feature — the future engine is responsible for calling `StorageProvider.save(...)` with
  the `SagaInstance` snapshot produced by a `SagaLifecycle` operation. No new `StorageProvider` method is
  introduced.
- **`EventDispatcher`** *(existing interface, unchanged)*: continues to perform event-to-instance routing
  only; it does not call `SagaLifecycle` or `SagaContext` itself — the future engine sits between dispatch
  resolution and lifecycle/context usage (see `research.md` §3).
- **Scanner (`AnnotationScanner`/`ReflectiveAnnotationScanner`)**: extended to recognize at most one
  `SagaContext`-typed field on an `@Saga` class (research.md §1). The existing `@SagaId` field rule, the
  `@SagaStep` single-`@DomainEvent`-parameter rule, and all other existing validation rules are unchanged.
