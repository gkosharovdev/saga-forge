# Feature Specification: Saga Lifecycle and Context Abstractions

**Feature Branch**: `001-core-lifecycle-context`

**Created**: 2026-10-11

**Status**: Draft

**Input**: User description: "Refine saga-forge-core's runtime SPI by introducing two distinct, clearly-scoped abstractions that currently don't exist in code despite being referenced in docs/AGENTS.md as implemented: (1) SagaLifecycle — the process-orchestration-level API: starting a saga instance, ending/completing it, triggering the next step, and other whole-instance lifecycle transitions. (2) SagaContext — the step-level, business-state API available to a @SagaStep method during execution: getState, putState, updateStateAttribute, scoped to the current saga instance. Both concepts are needed and operate at different levels. Define both distinctly, their relationship to each other and to the existing SagaInstance record and StorageProvider/EventDispatcher SPIs. Resolve the runtime type a step method receives, whether state access is typed, the serialization contract implied for StorageProvider, and how the stale 'already implemented' documentation claim gets reconciled."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Step author reads and writes business state across steps (Priority: P1)

A developer authoring a `@Saga` class writes a `@SagaStep` method that needs a value produced by
an earlier step of the same saga instance (e.g. a value computed in step 1 that step 3 needs, without
re-deriving it from event payloads or threading it manually through every intermediate event). The
developer stores that value by name when it is produced, and retrieves it by name and expected type in
a later step of the same instance.

**Why this priority**: This is the highest-priority documented feature gap (queryable process-level
state). Without it, saga authors fall back to hand-rolled state-threading through event payloads, which
is exactly the kind of imperative plumbing the framework exists to replace.

**Independent Test**: Can be fully tested by defining a saga with two steps where the first step writes
a named value and the second step reads it back by name and type, independent of any lifecycle or
storage-provider implementation.

**Acceptance Scenarios**:

1. **Given** a running saga instance, **When** a step writes a named business-state value, **Then** a
   later step of the same instance can read that value back by the same name.
2. **Given** a running saga instance with no value stored under a given name, **When** a step attempts
   to read that name, **Then** the API clearly indicates the value is absent rather than returning a
   null or default value indistinguishable from "not set."
3. **Given** a named value stored as one type, **When** a later step reads it back requesting a
   different, incompatible type, **Then** the API reports a clear type-mismatch error rather than
   returning corrupted data or throwing an unchecked `ClassCastException` deep in business logic.
4. **Given** two different saga instances, **When** each writes a value under the same name, **Then**
   each instance only ever reads back its own value — state is never visible across instances.
5. **Given** a named structured value already stored (e.g. an object with several properties),
   **When** a step updates a single field of that value by name, **Then** a later step reading the
   full value observes the updated field with all other fields unchanged.
6. **Given** no value stored yet under a given name, **When** a step updates a single field of that
   name, **Then** a later step reading the full value observes a structured value containing just that
   field.
7. **Given** a scalar (non-structured) value already stored under a name, **When** a step attempts a
   field-level update on it, **Then** the API reports a clear error rather than silently replacing or
   corrupting the existing value.

---

### User Story 2 - Framework governs saga instance lifecycle transitions (Priority: P1)

The (not-yet-built) saga engine needs a single, coherent contract for driving a saga instance through
its lifecycle: creating/starting a new instance, advancing to the next step once a step's work is done,
and marking an instance complete. Today no such contract exists in code — engine behavior would
otherwise have to be invented ad hoc by whoever builds the engine, inconsistently with how
`@StartSaga`/`@EndSaga` declare lifecycle boundaries on the annotated saga class.

**Why this priority**: Without this contract, there is no agreed shape for the engine to drive
instances, and future engine work has nothing authoritative to implement against — risking a repeat of
the exact ad hoc, non-reusable plumbing this framework is meant to replace.

**Independent Test**: Can be fully tested by exercising the lifecycle contract directly (start a new
instance, trigger a step advance, mark complete) against a test double, independent of any real engine,
storage, or transport implementation.

**Acceptance Scenarios**:

1. **Given** no existing instance for a given saga type and correlation value, **When** the lifecycle
   contract is asked to start a new instance, **Then** a new instance is created in an initial,
   well-defined status.
2. **Given** a running instance, **When** the lifecycle contract is asked to advance it to its next
   step, **Then** the instance's recorded progress reflects that advancement.
3. **Given** a running instance whose saga class marks the current step `@EndSaga`, **When** the
   lifecycle contract is asked to complete the instance, **Then** the instance transitions to a
   terminal "completed" status and no further step advancement is accepted for it.
4. **Given** an instance already in a terminal status, **When** the lifecycle contract is asked to
   advance or complete it again, **Then** the request is rejected with a clear error rather than
   silently succeeding or corrupting the recorded status.

---

### User Story 3 - Documentation accurately reflects what is implemented (Priority: P2)

A contributor (human or agent) reads `AGENTS.md` / `docs/architecture.md` to understand what exists in
`saga-forge-core` today. Those documents currently assert that `SagaContext`/`SagaLifecycle` are
"implemented and tested," which is not true — neither exists in code. Contributors who trust this claim
make incorrect assumptions and waste effort looking for code that was never written.

**Why this priority**: Documentation accuracy is a prerequisite for the project's own stated source-of-
truth hierarchy to function; a false "implemented" claim actively misleads rather than merely omitting
information.

**Independent Test**: Can be fully tested by cross-referencing the "Current Status" claims in `AGENTS.md`
and `docs/architecture.md` against the actual contents of `saga-forge-core` after this feature lands,
independent of any other feature work.

**Acceptance Scenarios**:

1. **Given** this feature is delivered, **When** a contributor reads `AGENTS.md`'s "Current Status"
   section, **Then** it accurately lists `SagaLifecycle` and `SagaContext` as implemented only once they
   exist in code, and did not claim so beforehand.
2. **Given** this feature is delivered, **When** a contributor reads `docs/architecture.md`, **Then**
   its description of these two abstractions matches their actual, shipped shape and scope.

---

### Edge Cases

- A step requests a named state value that was never written for this instance — MUST be reported as
  absent, not as null or a type-default value (resolved by FR-004).
- A step writes a named state value before the saga instance has reached a started/running status —
  behavior is out of scope for this spec; see Assumptions (the lifecycle and context contracts are
  defined independently of enforcing call-order between them in this iteration).
- Two step invocations for the same instance (e.g. due to at-least-once redelivery) concurrently write
  to the same named state value, or concurrently update the same field of it — conflict resolution and
  idempotency are explicitly out of scope for this spec; tracked separately under the idempotent step
  execution feature gap.
- A step addresses a field within a named value that does not yet exist — resolved by FR-002b (upsert:
  a new structured value is created holding just that field).
- A step attempts a field-level update on a named value that already exists but is a plain scalar, not
  a structured value — resolved by FR-002c (reported as a clear error, not silently overwritten).
- The lifecycle contract is asked to start an instance for a saga type/correlation pair that already has
  a running instance — MUST be reported as a conflict rather than silently creating a duplicate or
  silently reusing the existing instance (resolved by FR-006).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The framework MUST provide a step-level contract (distinct from the persistence-shaped
  saga instance record) through which a `@SagaStep` method can read a named business-state value
  associated with the current saga instance.
- **FR-002**: The framework MUST provide a step-level contract through which a `@SagaStep` method can
  write (create or replace) a named business-state value associated with the current saga instance, such
  that it becomes visible to subsequent steps of the same instance.
- **FR-002a**: The framework MUST support updating a single named field within an already-stored
  structured state value (e.g. one property of a stored object) without requiring the caller to
  resupply the value's other fields — a distinct operation from the whole-value replace in FR-002.
- **FR-002b**: If no value currently exists under the target name, updating a field on it MUST create a
  new structured value containing only that field (upsert semantics), rather than failing.
- **FR-002c**: If a value already exists under the target name but is not a structured value capable of
  holding named fields (e.g. it is a plain scalar), attempting a field-level update on it MUST report a
  clear, distinguishable error rather than silently overwriting it with a new structure or corrupting
  the existing value.
- **FR-003**: Reading a named state value MUST be type-safe: the caller specifies the expected type, and
  the contract MUST report a clear, distinguishable error when the stored value does not match the
  requested type, instead of requiring the caller to perform an unchecked cast.
- **FR-004**: Reading a named state value that was never written MUST be clearly distinguishable from
  reading a value that was written as a null/empty/default value — the two cases MUST NOT be
  indistinguishable to the caller.
- **FR-005**: The framework MUST provide a process-orchestration-level contract, distinct from the
  step-level state contract, that governs: starting a new saga instance, advancing an instance to its
  next step, and marking an instance complete.
- **FR-006**: The process-orchestration-level contract MUST reject lifecycle transitions that are
  invalid for an instance's current status (e.g. starting a duplicate instance for an already-running
  correlation, or completing/advancing an instance already in a terminal status) with a clear,
  distinguishable error.
- **FR-007**: The step-level state contract and the process-orchestration-level contract MUST be
  specified as two distinct abstractions, each with a documented relationship to the other and to the
  existing `SagaInstance` persistence record, so a reader cannot confuse "business state I am reading or
  writing" with "lifecycle transition I am causing."
- **FR-008**: The specification MUST identify which consumer uses each contract (the code inside a
  `@SagaStep` method body, versus the saga engine/runtime that drives instances) so that implementers of
  later modules (engine, storage providers) have an unambiguous contract to build against.
- **FR-009**: Named business-state values written through the step-level contract — including
  individual fields updated via FR-002a — MUST be representable in a form a storage-provider
  implementation can persist and later reconstruct, without `saga-forge-core` depending on a specific
  storage technology to do so.
- **FR-010**: State scoped to one saga instance MUST never be visible to, or writable by, a different
  saga instance, regardless of saga type.
- **FR-011**: `AGENTS.md` and `docs/architecture.md` MUST NOT claim `SagaLifecycle`/`SagaContext` are
  implemented until they exist in code as specified here; the existing incorrect claim MUST be corrected
  as part of delivering this feature.

### Key Entities

- **Saga Lifecycle**: the process-orchestration contract governing a saga instance's start, step
  advancement, and completion transitions. Consumed by the saga engine/runtime that drives instances
  through their declared `@StartSaga`/`@EndSaga` boundaries — not by step-author business logic.
- **Saga Context**: the step-execution contract exposing named, type-safe business-state read/write,
  scoped to exactly one saga instance, available to a `@SagaStep` method during its execution. A named
  value may be a structured value whose individual fields can be updated independently (FR-002a) as
  well as replaced wholesale (FR-002).
- **Saga Instance** *(existing)*: the persistence-shaped record a `StorageProvider` saves and loads.
  This feature defines how the business state a Saga Context exposes relates to the state already
  carried by this record, without altering `StorageProvider`'s existing contract.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A saga author can make a value produced in one step available to a later step of the same
  instance using only the step-level contract — no custom storage, threading, or event-payload
  re-derivation code required.
- **SC-002**: Reading a named state value as the wrong type produces an immediate, clear error at the
  point of the read call, not a silent incorrect value or a failure elsewhere in business logic.
- **SC-003**: Zero remaining instances of "implemented and tested" claims in project documentation for
  abstractions that do not yet exist in code, verified by cross-referencing documentation against the
  codebase after delivery.
- **SC-004**: A contributor reading the two contracts' specifications, without reading any implementation
  code, can correctly state which one a step author calls and which one governs instance-level
  transitions.
- **SC-005**: A saga author can update one field of a previously stored structured state value without
  re-supplying the entire value and without risk of unrelated fields being overwritten or lost.

## Assumptions

- `SagaLifecycle` is consumed by the saga engine/runtime (a future module), not called directly from
  inside `@SagaStep` method bodies — step authors express lifecycle boundaries declaratively via
  `@StartSaga`/`@EndSaga`, and the engine is what acts on those declarations through `SagaLifecycle`.
- State scoped by `SagaContext` is strictly per-saga-instance; cross-instance querying of business state
  is out of scope for this spec and not assumed to be needed, consistent with the project's narrow-scope
  principle.
- Enforcing call-order between `SagaLifecycle` transitions and `SagaContext` state access (e.g.
  preventing state writes before an instance is formally started) is out of scope for this iteration;
  this spec defines the two contracts' shapes and relationship, not cross-contract invariant enforcement.
- Concurrency/idempotency conflicts on state writes (e.g. from at-least-once event redelivery) are out of
  scope here and remain tracked under the separate idempotent step execution feature gap.
- Persistence mechanics (how a `StorageProvider` implementation actually serializes and stores state
  values) are a following, dependent feature; this spec only establishes the representational contract
  core must expose, not a storage implementation.
