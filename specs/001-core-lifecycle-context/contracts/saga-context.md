# Contract: `SagaContext`

**Package**: `io.github.gkosharovdev.sagaforge.core.lifecycle` | **Kind**: interface, no implementation
shipped in this feature | **Consumer**: code inside a `@SagaStep` method body (FR-008) — reached via a
type-recognized `SagaContext`-typed field on the `@Saga` class (see `research.md` §1), never constructed
directly by step-author code.

## Purpose

Gives a `@SagaStep` method typed, per-instance read/write access to named business-state values produced
or needed across steps of the same saga instance (FR-001 through FR-004, FR-002a/b/c, FR-010). Distinct
from `SagaLifecycle`, which governs instance-level transitions (FR-007).

## Operations

### `<T> Optional<T> getState(String name, Class<T> type)`

- **Pre-conditions**: `name` non-null/non-blank, `type` non-null.
- **Effect**: returns `Optional.of(value)` if a value is stored under `name` and is assignable to `type`.
  Returns `Optional.empty()` if and only if no value was ever stored under `name` (FR-004).
- **Failure**: throws `SagaStateTypeMismatchException` if a value exists under `name` but is not
  assignable to `type` (FR-003) — never an unchecked `ClassCastException`, never a silently wrong value.

> **Note on `null`**: `null` values are rejected on write (`putState`/`updateStateAttribute` throw
> `IllegalArgumentException`), so there is no stored-`null` case: `Optional.empty()` always means "never
> written".

### `void putState(String name, Object value)`

- **Pre-conditions**: `name` non-null/non-blank; `value` non-null. Violations throw
  `IllegalArgumentException`.
- **Effect**: creates a new value under `name`, or wholesale-replaces whatever was previously stored
  there (FR-002). Visible to this and later steps of the same instance via `getState`/`updateStateAttribute`.

### `void updateStateAttribute(String name, String field, Object value)`

- **Pre-conditions**: `name` and `field` non-null/non-blank; `value` non-null. Violations throw
  `IllegalArgumentException`.
- **Effect**: mutates exactly the named `field` within the structured value stored under `name`, leaving
  all of that value's other fields unchanged (FR-002a, SC-005). If no value exists yet under `name`,
  creates a new structured value containing only `field` (FR-002b — upsert, not a failure).
- **Failure**: throws `NonStructuredStateValueException` if a value already exists under `name` but is a
  scalar (not structured) — the existing value is left untouched, not partially overwritten (FR-002c).

## Scoping invariant (FR-010)

A `SagaContext` instance is bound to exactly one saga instance for its entire lifetime. No operation on it
can read or write another instance's state, regardless of saga type — two different instances writing
under the same `name` never observe each other's values.

## Relationship to other SPI contracts

- **`SagaInstance`**: named values (and structured values' individual fields) map 1:1 onto entries of the
  existing `SagaInstance.state : Map<String, Object>` field — no new persisted shape (`research.md` §2).
- **`StorageProvider`**: not called directly by `SagaContext`. The engine folds the working state a
  `SagaContext` accumulated during a step invocation back into the `SagaInstance.state` map before the
  engine calls `StorageProvider.save(...)`.
- **`SagaLifecycle`**: entirely separate consumer path (engine code, not step-author code). See
  `saga-lifecycle.md`.

## Independent test shape (per spec User Story 1)

Exercisable with a saga defining two steps, independent of any lifecycle or storage implementation:
1. Step 1 `putState("x", v)`; step 2 `getState("x", T.class)` → returns `v`.
2. `getState` on a name never written → `Optional.empty()`.
3. A value stored as type `A`; `getState(name, B.class)` where `B` is incompatible → `SagaStateTypeMismatchException`.
4. Two different instances each `putState` under the same `name` → each reads back only its own value.
5. `updateStateAttribute` on an existing structured value → other fields of that value are unchanged.
6. `updateStateAttribute` on a name with no existing value → new structured value with just that field.
7. `updateStateAttribute` on an existing scalar value → `NonStructuredStateValueException`, value untouched.

## 004 delta (2026-11-09, `specs/004-saga-engine`)

Source: `specs/004-saga-engine/contracts/contract-deltas.md`. Where this contract says "the future engine", read `saga-forge-engine`.

"Relationship to other SPI contracts" (values map onto `SagaInstance.state`) was already superseded by 002 and is
restated: `SagaContext` values live in a `SagaContextRecord` behind `SagaContextStore`, written through after every
`putState`/`updateStateAttribute` by the engine's `EngineSagaContext`, never inside the unit of work's transaction.
`SagaInstance.state` stays empty (G-ENG-06).
