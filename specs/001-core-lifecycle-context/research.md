# Phase 0 Research: Saga Lifecycle and Context Abstractions

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md)

This document resolves the open design questions raised by the spec (FR-005 through FR-009, and the
runtime-type/typed-access/serialization questions named in the original feature description) before any
data model or contract is written. Each decision follows Decision / Rationale / Alternatives Considered.

## 1. How does a `@SagaStep` method body obtain a `SagaContext`?

**Decision**: Type-based field injection. A `@Saga` class may declare at most one field of type
`SagaContext` (an interface). `ReflectiveAnnotationScanner` recognizes this field by its declared type,
exactly as it already recognizes the `@SagaId`-annotated field — but without requiring a new marker
annotation, since the type itself is unambiguous in this position. The (future) engine populates this
field with a `SagaContext` instance scoped to the current saga instance immediately before invoking a
`@SagaStep` method, and may clear or leave it bound between invocations (an implementation detail for the
engine module, not specified here).

**Rationale**:
- Preserves the already-implemented, already-tested `@SagaStep` validation rule that a step method takes
  exactly one parameter (the `@DomainEvent`-typed one) — spec does not request changing this, and doing so
  for an unrelated capability would be scope creep against Principle IV.
- Keeps the mechanism declarative (a field declaration on the class, inspectable by reading the class),
  consistent with Principle II, rather than ambient/implicit state.
- Mirrors a pattern the scanner already implements for `@SagaId`, minimizing new scanner concepts.

**Alternatives considered**:
- *Relax `@SagaStep` to accept an optional second, `SagaContext`-typed parameter.* Rejected: requires
  changing a validation rule with existing test coverage (`StepWithMultipleParamsSaga`,
  `StepWithNoParamSaga` fixtures) for no reason the spec asks for, and conflates "what event triggered
  this step" with "what context is available during it" in a single parameter list.
- *`ThreadLocal`-based `SagaContext.current()` static accessor.* Rejected: technically workable for a
  single synchronous dispatch call stack (the durability/cross-process concern that disqualifies
  `ThreadLocal` elsewhere does not apply within one in-process method call), but it hides the dependency as
  ambient global state rather than a declared, readable field — conflicts with Principle II's declarative
  preference and is harder to unit test (requires simulating thread-local setup/teardown instead of plain
  object construction).
- *Require step authors to accept `SagaContext` as a constructor-injected dependency of the `@Saga`
  class itself.* Rejected: conflates per-saga-class construction (once) with per-instance, per-invocation
  scoping (every step call, for a specific saga instance) — a single `@Saga` class instance may need to
  serve many concurrent saga instances, so the context must be associated with the *invocation*, not the
  class instance's lifetime. Field injection by the engine immediately before each step method invocation
  satisfies this; constructor injection does not.

## 2. How is a named `SagaContext` state value represented and stored?

**Decision**: `Map<String, Object>`. Each named value is one entry. A "structured value" (FR-002a/b/c) is
represented as a nested `Map<String, Object>` under that name, whose own entries are the independently
addressable fields; a "scalar value" is any non-`Map` entry. This is exactly the shape the existing
`SagaInstance.state` field already uses (`Map<String, Object> state`, "serialized saga field state").

**Rationale**:
- FR-009 requires named values to be representable in a form a `StorageProvider` can persist without
  `saga-forge-core` depending on a storage technology. `SagaInstance.state` already has this exact shape
  today, and every planned `StorageProvider` implementation (Postgres/MySQL/MongoDB) already has to handle
  persisting it — reusing the shape means **no new serialization contract is introduced by this feature**.
  `SagaContext`'s named values are simply the semantics layered onto particular entries of a map shape that
  already exists in the SPI; the engine folds `SagaContext`'s working state back into `SagaInstance.state`
  before calling `StorageProvider.save()`.
- Field-level update (FR-002a) becomes a one-level nested-map mutation: resolve or create the nested map
  under `name`, then `put(field, value)` — directly satisfies the upsert semantics of FR-002b (absent name
  → new empty nested map, then populated) without a separate code path.
- FR-002c (field update on an existing non-structured value) becomes a single, cheap type check: if the
  existing entry under `name` is present and is not a `Map`, reject with `NonStructuredStateValueException`
  before mutating anything.

**Alternatives considered**:
- *Reflection-based mutation of arbitrary POJO fields.* Rejected: unjustified complexity for this spec's
  scope — requires reflective field access on caller-supplied types, fragile across refactors, and gains
  nothing over a map-of-maps given FR-009 already demands a storage-representable (i.e., structurally
  generic) shape rather than arbitrary object graphs.
- *A dedicated `StructuredValue` wrapper type distinct from `Map`.* Rejected: would require
  `StorageProvider` implementations and `SagaInstance.state` to understand a second, saga-forge-specific
  shape alongside the one they already handle — directly works against reusing the existing
  `Map<String, Object>` contract and adds a public type for no behavioral gain over a plain nested map.

## 3. How do `SagaLifecycle`, `EventDispatcher`, `StorageProvider`, and `SagaContext` relate?

**Decision**:
1. `EventDispatcher.dispatch(event)` resolves which saga instance(s) (if any) an incoming domain event
   correlates to, exactly as it does today (unchanged) — this is event-to-instance *routing*, not a
   lifecycle transition.
2. Once the (future) engine has resolved a target instance (or determined a new one must be created for a
   `@StartSaga` step), it calls into `SagaLifecycle` — `start`, `advance`, or `complete` — to perform the
   actual instance-level state transition. `SagaLifecycle` is defined in `saga-forge-core` as a contract
   only; **no implementation ships in this feature**, exactly like `StorageProvider`/`EventDispatcher`
   today. The future engine module is the sole implementer and sole caller.
3. Before invoking the `@SagaStep` method body, the engine constructs a `SagaContext` scoped to that one
   resolved instance and injects it via the field described in Decision 1. After the step method returns,
   the engine reads back whatever `SagaContext` recorded, folds it into the `SagaInstance.state` map it
   passes to `SagaLifecycle.advance(...)`, which is responsible (at the implementation level, in the future
   engine) for calling `StorageProvider.save(...)` to persist the resulting `SagaInstance` snapshot.
4. `SagaContext` is never consumed by `SagaLifecycle`-driving code and vice versa — the two contracts have
   disjoint consumers (step-author code vs. engine code), per FR-007/FR-008, and this spec defines no
   cross-contract call-order enforcement (per Assumptions).

**Rationale**: Keeps `SagaLifecycle` and `SagaContext` each single-purpose and reuses the already-defined
`SagaInstance`/`StorageProvider`/`EventDispatcher` shapes exactly as they exist today — this feature adds
two new contracts and corrects documentation, without modifying any existing SPI signature.

**Alternatives considered**:
- *Merge `SagaContext` state access into `SagaLifecycle` as a single contract.* Rejected: explicitly
  ruled out by FR-007 — the spec requires the two to be distinct abstractions with documented, separate
  consumers, specifically to prevent confusing "business state I am reading or writing" with "lifecycle
  transition I am causing."
- *Have `EventDispatcher` itself drive lifecycle transitions directly (skip a separate `SagaLifecycle`
  contract).* Rejected: `EventDispatcher`'s documented responsibility is event routing/delivery, which must
  remain transport-agnostic (Principle I); folding instance-lifecycle semantics into it would couple a
  transport-facing SPI to orchestration concerns it was not designed to own, and contradicts FR-005's
  requirement for a distinct process-orchestration-level contract.

## 4. How are error conditions signaled?

**Decision**: Dedicated unchecked (`RuntimeException`-derived) exception types, one per clearly distinct
failure mode named in the spec: `DuplicateSagaInstanceException` (FR-006, start-time correlation
collision), `InvalidSagaTransitionException` (FR-006, advance/complete on a terminal-status instance),
`SagaStateTypeMismatchException` (FR-003, wrong expected type on read), `NonStructuredStateValueException`
(FR-002c, field-update attempted on a scalar). Absence of a value (FR-004) is signaled by `Optional<T>`
returning empty — not an exception — since absence is an expected, non-error outcome the caller is
expected to branch on.

**Rationale**: Matches the codebase's existing error-signaling idiom — `StorageProvider`/`EventDispatcher`
already document `IllegalArgumentException` for null-argument violations, and the project has no
`Result`/`Either`-style wrapper type in use anywhere. Dedicated, named exception types satisfy FR-003's and
FR-006's "clear, distinguishable error" requirement precisely (callers can `catch` the specific type rather
than parse a message string or receive an unchecked `ClassCastException`), consistent with Effective Java
Item 73 (exception translation at API boundaries) and Item 69 (exceptions for genuinely exceptional
conditions — a wrong-type read or an invalid transition request both qualify; a missing value does not,
hence `Optional` rather than an exception for that case).

**Alternatives considered**:
- *A single shared `SagaLifecycleException`/`SagaContextException` with an error-code field.* Rejected:
  weaker than the type system — callers would need to inspect a code/enum at runtime instead of catching a
  specific type, and it does not match the existing codebase's preference for specific exception types
  over generic ones with embedded codes.
- *Returning a sealed `Result<T, Error>`-shaped outcome instead of throwing.* Rejected: not an idiom used
  anywhere else in `saga-forge-core` today; would introduce a second error-handling style alongside the
  existing `IllegalArgumentException`-throwing SPI methods, adding inconsistency rather than clarity.
