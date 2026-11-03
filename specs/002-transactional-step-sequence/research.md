# Phase 0 Research: Saga Units of Work, Command Gateway, and Command Handlers

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md)

Each section settles one design question the spec left to planning. Format: Decision, Rationale,
Alternatives considered.

## 1. How a step reaches the command gateway

**Decision**: A `@Saga` class declares at most one field of type `CommandGateway`, recognized by type with no
marker annotation, exactly like the `SagaContext` field from feature 001. The engine populates it before each
step call. The scanner rejects more than one such field.

**Rationale**: Reuses the idiom 001 already justified (Complexity Tracking in 001's plan.md) instead of
introducing a second one. The `@SagaStep` "exactly one `@DomainEvent` parameter" rule stays untouched. Services
and application code are ordinary beans and obtain `CommandGateway` and `EventPublisher` through their own
dependency injection; only saga instances, which the engine creates, need the field.

**Alternatives considered**: A second step-method parameter (rejected: changes a tested scanner rule for no
spec-mandated reason). A static `CommandGateway.current()` accessor (rejected for the same reason 001 rejected
`SagaContext.current()`: hidden ambient state). A marker annotation on the field (rejected: ceremony for a type
that is already unambiguous).

## 2. Command gateway contract

**Decision**: `CommandGateway` is a core interface with one operation, `void send(Object command)`. It is
synchronous: the matching `@CommandHandler` runs to completion inside the caller's current unit of work before
`send` returns. A handler failure propagates out of `send` unchanged and marks the unit of work for rollback
(spec FR-012), so catching it in the step does not prevent the rollback. A command with no handler raises
`NoCommandHandlerException`. A non-`@Command` argument raises `IllegalArgumentException`.

**Rationale**: Synchronous, in-unit-of-work execution is what makes the chain of in-process events atomic. The
handler's return value is not needed, because events reach the framework through aggregate recording or the
event publisher (spec Clarifications), not through return values.

**Alternatives considered**: Axon-style `send` returning a future plus `sendAndWait` (rejected for now: an
asynchronous variant cannot share the unit of work, and no spec requirement needs a handler result).
Fire-and-forget dispatch through the transport (rejected: breaks FR-019, steps of one unit of work must not
cross an asynchronous transport).

## 3. How the gateway finds and invokes a handler, with persistence left to client code

**Decision**: Routing is purely by scanning: each command type maps to its single `@CommandHandler`. Client code
never implements a Saga Forge interface to get its aggregates loaded or saved (spec FR-013):

- **Service-hosted handler**: the engine obtains the service instance from a `HandlerInstanceProvider` SPI
  implemented by the host integration (Spring starter: the application context; harness: a map).
- **Creation handler**: a constructor or static factory of an `@Aggregate` annotated `@CommandHandler`. The
  engine invokes it and then saves the new aggregate.
- **Instance-method handler on an `@Aggregate`**: the engine reads the command's `@AggregateIdentifier`, loads
  the aggregate, invokes the handler, then saves it. Loading uses, in order: a client method annotated
  `@AggregateLoader` for that aggregate type (any non-saga class; one parameter, the id; returns the
  aggregate); otherwise the host integration's `AggregateResolver`. Saving uses a client `@AggregateSaver`
  method if present, otherwise the resolver's save, otherwise the client's own unit of work (e.g. JPA dirty
  checking).
- `AggregateResolver` is a core SPI implemented by host integrations only. The Spring starter will adapt the
  client's Spring Data repositories (JPA for Postgres/MySQL, Spring Data Mongo). The harness provides an
  in-memory one.
- A missing aggregate fails the unit of work (`AggregateNotFoundException`). An `@Aggregate` with
  instance-method handlers and neither a loader nor a resolver that supports it is reported when the engine
  starts; the scanner cannot see host resolvers, so this check runs at engine startup rather than in
  `validateAll`.

**Rationale**: Matches the user's requirement that persistence stays entirely in client code. The only Saga
Forge types client code touches are marker annotations; adapters for existing repositories live in the
starter.

**Alternatives considered**: A client-implemented `AggregateRepository<A>` SPI (rejected by the user: forces a
Saga Forge interface into client code). Service-hosted handlers only (kept as a fallback, rejected as the only
option: it removes handlers on aggregates). A separate `@TargetAggregateIdentifier` for commands (rejected in
clarification: one `@AggregateIdentifier` annotation serves aggregates, commands, and events).

## 4. How domain events are recorded and intercepted

**Decision**: A static facade `DomainEvents` in `core.event`:

- `static void record(Object domainEvent)` adds the event to the active unit of work's recorder. It works from
  any depth (aggregate, domain service, handler) and needs no base class or interface, so aggregates may be
  state-stored or event-sourced. With no active unit of work it throws `IllegalStateException`.
- `static List<Object> capture(Runnable action)` is a testing aid: it runs `action` with a temporary recorder
  and returns what was recorded, so aggregates are unit-testable without the framework.
- The engine binds a recorder for the duration of a unit of work through an engine-facing SPI,
  `DomainEventScope.runWith(DomainEventRecorder recorder, Runnable work)`, backed by a `ThreadLocal`
  (`ScopedValue` is still a preview API in Java 21). Nested bindings restore the outer recorder on exit.
- Every `@DomainEvent` names its aggregate, `@DomainEvent(aggregate = X.class)` with `X` an `@Aggregate`, and
  carries exactly one `@AggregateIdentifier` field. Saga Forge does not check that an event follows a real state
  transition; that is the client's responsibility.

**Rationale**: Axon's `AggregateLifecycle.apply` ergonomics without its event-sourcing implication. "Record"
describes what happens: the fact is noted now and takes effect when the unit of work commits. Synchronous
handler execution keeps every call on the unit of work's thread.

**Alternatives considered**: A `RecordsDomainEvents` interface plus base class collected after the handler
(rejected by the user: forces aggregates to implement or inherit a Saga Forge type). `apply` (event-sourcing
connotation), `raise` (often means immediate dispatch), `publish` (reserved for `EventPublisher`). Silently
dispatching when no unit of work is active (rejected: a state change outside a transaction is almost always a
bug, so fail fast).

## 5. Event publisher and delivery

**Decision**: `EventPublisher` is a core interface with `void publish(Object domainEvent)`; a non-`@DomainEvent`
argument raises `IllegalArgumentException`. It is injectable, meant for application code (typically outside a
unit of work, e.g. to start a saga), and distinct from the `EventDispatcher` transport SPI. Inside a unit of
work, `publish` is equivalent to `DomainEvents.record`. Engine behavior (spec FR-017):

- Inside a unit of work, each recorded or published event is checked against the steps of the saga instance
  that owns the unit of work. A match (same event type and same `associationProperty` value) is queued for
  in-process delivery within the same unit of work, in the order raised.
- Every other event is buffered and handed to `EventDispatcher.dispatch` only after the unit of work commits;
  the buffer is discarded on rollback.
- Outside any unit of work, `publish` hands the event to `EventDispatcher.dispatch` immediately.

**Rationale**: One delivery rule for every event, however it was recorded, keeps aggregate-hosted and
service-hosted handlers equivalent. Reusing `EventDispatcher` for after-commit delivery means the transport
modules need no new SPI.

**Alternatives considered**: Letting `EventPublisher` call `EventDispatcher` directly every time (rejected: an
event would leave the process before its unit of work commits, and could not be withdrawn on rollback).

## 6. Where `SagaContext` and saga bookkeeping are stored

**Decision**:

- `SagaContext` values are stored as their own record, `SagaContextRecord(sagaId, values, lastModifiedAt)`,
  through a new `SagaContextStore` SPI (`find`, `save`, `delete`). Storage modules implement it next to
  `StorageProvider`, as a separate table (Postgres, MySQL) or collection (MongoDB). The engine writes context
  changes outside the saga-scoped unit of work, so they survive a rollback (spec FR-024). Feature 001's value
  semantics (scalars, structured maps, type-mismatch errors, null rejection) are unchanged.
- `SagaInstance.state` keeps its original meaning, the saga class's own serialized fields. It no longer holds
  `SagaContext` values. This supersedes 001 research Decision #2.
- `SagaInstance` gains per-step records (`Map<String, StepRecord>`: status plus the `AggregateReference`s,
  aggregate type and id, of every event recorded while the step ran, spec FR-004), the reached outcome (the
  `@EndSaga` step name), and a failure record. Step records and the outcome commit with the unit of work. The
  touched aggregates come from each event's `@DomainEvent(aggregate = ...)` and `@AggregateIdentifier` value,
  and are what the compensation spec will use to find the aggregates to undo. The failure record
  is written after the rollback, in its own write, so a failed first unit of work still leaves a `FAILED`
  instance (spec FR-021).

**Rationale**: This is what the user specified (a separate record referencing the saga by id), and it
separates "survives rollback" data from "commits with the unit of work" data cleanly. A separate SPI keeps
`StorageProvider` focused and lets storage modules decide how to run the independent write.

**Alternatives considered**: Adding context methods to `StorageProvider` (rejected: mixes two write
disciplines, transactional and independent, in one SPI). Keeping context in `SagaInstance.state` (rejected by
the user's clarification).

## 7. Validation across several classes

**Decision**: `AnnotationScanner.validate(Class<?>)` keeps validating one class. A new
`validateAll(Collection<Class<?>> classes)` operation validates a whole model in one pass and returns a
`ValidationReport`: one `SagaValidationResult` per validated class (sagas and handler-hosting classes) plus
model-level errors that involve more than one class (a command type with several handlers, several loaders or
savers for one aggregate type). Event and command types are validated when passed in or when reached from a
step or handler parameter. `isValid()` is true only if every part is valid. New rules:

| Rule | Scope | Spec |
|---|---|---|
| A `@SagaStep` method returns `void` | saga class | FR-006 |
| No two `@SagaStep` methods of a saga react to the same event type | saga class | FR-001 |
| A `@Saga` class declares no `@CommandHandler` method | saga class | FR-010 |
| At most one `CommandGateway`-typed field on a `@Saga` class | saga class | research §1 |
| A `@CommandHandler` method has exactly one `@Command`-typed parameter | handler class | FR-009 |
| No command type is handled by more than one `@CommandHandler` | model | FR-011 |
| Exactly one `@AggregateIdentifier` field on each `@Aggregate` and each `@DomainEvent` type; at most one on each `@Command` type | aggregate, event, command | FR-014 |
| A command handled by an `@Aggregate` instance-method handler declares an `@AggregateIdentifier` | model | FR-014 |
| `@DomainEvent(aggregate = X.class)` names an `@Aggregate` class | event | FR-014 |
| `@AggregateLoader`: one parameter, returns an `@Aggregate` type; `@AggregateSaver`: one `@Aggregate`-typed parameter | loader/saver class | FR-013 |
| At most one `@AggregateLoader` and one `@AggregateSaver` per aggregate type | model | FR-014 |

Existing rules (exactly one `@SagaId`, exactly one `@StartSaga`, at least one `@EndSaga`, co-occurrence,
single `@DomainEvent` parameter, `forStep` validity, at most one `SagaContext` field) are unchanged.

**Rationale**: FR-026 asks for all errors in one pass "through the existing scanner validation result". Reusing
`SagaValidationResult` per class and adding a thin report for cross-class errors avoids a second result type
for per-class problems.

**Alternatives considered**: A separate `CommandHandlerScanner` with its own result type (rejected: callers
would have to merge two reports to satisfy the single-pass requirement).

## 8. Extending feature 001's `SagaLifecycle`

**Decision**: `SagaLifecycle` stays engine-facing (001 FR-008; abort was dropped, so steps never call it).
`SagaStatus` is unchanged. The contract changes as follows:

| Operation | Change | Status effect |
|---|---|---|
| `start(sagaType, associationProperty, associationValue)` | unchanged | → `CREATED` |
| `recordStepCompleted(sagaId, stepName, touchedAggregates)` | new | none; marks the step `COMPLETED` and stores the aggregates it touched |
| `advance(sagaId)` | meaning narrowed: called when a unit of work commits at a wait point | `CREATED`/`RUNNING` → `RUNNING` |
| `complete(sagaId, outcomeStep)` | replaces `complete(sagaId)`; records the outcome | → `COMPLETED` |
| `fail(sagaId, failure)` | new | `CREATED`/`RUNNING` → `FAILED` |

Terminal statuses remain `COMPLETED` and `FAILED`; `COMPENSATING` is still never entered. The 001 concern that
`complete` cannot verify its `@EndSaga` pre-condition stays: verifying that `outcomeStep` is an `@EndSaga` step
remains the engine's responsibility, because `SagaLifecycle` holds no saga metadata.

**Rationale**: Smallest change that covers per-step status (FR-004), outcome (FR-005) and failure (FR-021)
while keeping 001's transition model and exception types.

**Alternatives considered**: A new engine-facing contract replacing `SagaLifecycle` (rejected: discards tested
001 work for no benefit). Keeping `complete(sagaId)` and adding a separate `recordOutcome` (rejected: two calls
that must always happen together).

## 9. The saga-scoped unit of work: contract, not SPI, in this feature

**Decision**: The unit-of-work rules (spec FR-017 to FR-022) are specified as an engine execution contract in
`contracts/saga-unit-of-work.md` and verified by a test-only `SagaTestHarness`. No transaction SPI is added to
core in this feature; how the production engine opens, commits and rolls back the host application's local
transaction is decided in the engine feature.

**Rationale**: Same posture as 001: ship contracts plus a test double that proves them. Committing to a
transaction SPI before the engine exists risks designing it twice. The harness becomes the reference model the
engine must match.

**Alternatives considered**: A `UnitOfWork` / `TransactionBoundary` SPI now (deferred to the engine feature).
Implementing the engine now (out of the spec's scope).

## 10. Test harness design

**Decision**: `SagaTestHarness` lives in `saga-forge-core/src/test/.../execution/harness/`. It is built from
the same `validateAll` model, registers handler instances, an in-memory `AggregateResolver`, and loader/saver
fixtures, and reuses the 001 doubles where possible. It simulates a unit of work by snapshotting the in-memory
aggregate store and the saga instance store at the start and restoring them on rollback, while the in-memory
`SagaContextStore` is never snapshotted, so context survives rollback. It processes one unit of work per
instance at a time, which satisfies FR-022 for the harness. A `persistedState()` / `fromPersistedState(...)`
pair lets a fresh harness resume a waiting saga (spec SC-003).

**Rationale**: Directly exercises every acceptance scenario of US1 to US3 without an engine, storage or
transport.

**Alternatives considered**: Testing only the scanner and the contracts' Javadoc (rejected: SC-002 to SC-004
are behavioral and need an executable model).

## 11. Concurrency (FR-022)

**Decision**: The contract requires the engine to serialize units of work per saga instance. The persistence
mechanism that guarantees it across application instances (for example an optimistic version on
`SagaInstance`) is deferred to the engine and storage features; this feature adds no version field.

**Rationale**: The guarantee belongs where concurrent writers exist. Adding a version field now would force
every storage module to honor a rule no code exercises yet.

**Alternatives considered**: Adding `long version` to `SagaInstance` now (deferred; noted in the contract so
the engine feature picks it up).

## 12. Documentation and historical specs

**Decision**: US4 updates the README, `docs/use-cases.md`, `docs/architecture.md` (vocabulary and the
queryable-state section), `AGENTS.md` (saga model), 001's `quickstart.md` and `SagaContext` Javadoc
(representation section), and the `saga-forge-example` module.
`.kiro/specs/saga-forge-framework/requirements.md` Requirement 8.4 and Requirement 11 get a "Superseded by
specs/002-transactional-step-sequence" note, and `design.md` gains correctness properties for the new rules
(Principle III). The constitution's Technical Constraints still say "`SagaContext` tracks step history"; a
PATCH amendment (1.0.0 → 1.0.1) corrects it.

**Rationale**: Principle III requires the formal specs to track annotation-semantics changes; Principle V
requires docs not to contradict code.

**Alternatives considered**: Leaving the `.kiro` specs untouched as pure history (rejected: they would silently
contradict FR-006, which US4 scenario 3 forbids).
