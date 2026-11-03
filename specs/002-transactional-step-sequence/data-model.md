# Phase 1 Data Model: Saga Units of Work, Command Gateway, and Command Handlers

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md) | **Research**: [research.md](./research.md)

All types live in `saga-forge-core` and use JDK types only. "New" types are added by this feature; "changed"
types are existing types whose shape changes. Base package: `io.github.gkosharovdev.sagaforge.core`.

## Annotations (`annotation/`)

| Type | Target | Attributes | Meaning |
|---|---|---|---|
| `@CommandHandler` *(new)* | method, constructor | none | Handles the one `@Command`-typed parameter (FR-008, FR-009). On an `@Aggregate`: an instance method (target loaded first) or a constructor / static factory (creation). On a service: a method. Never on `@Saga` classes (FR-010). |
| `@Aggregate` *(new)* | type | none | Marks a consistency boundary. Loaded and saved by client code only (FR-013); extends and implements no Saga Forge type. |
| `@AggregateIdentifier` *(new)* | field | none | Exactly one on each `@Aggregate` and `@DomainEvent` type, at most one on each `@Command` type (FR-014). On a command it names the target aggregate. |
| `@AggregateLoader` *(new)* | method | none | Client method that loads one aggregate type from its id: one parameter, returns an `@Aggregate` type. At most one per aggregate type. |
| `@AggregateSaver` *(new)* | method | none | Client method that persists one aggregate type: one `@Aggregate`-typed parameter. Optional; at most one per aggregate type. |
| `@DomainEvent` *(changed)* | type | `Class<?> aggregate()` (required) | Names the `@Aggregate` type the event is about (FR-014). |
| `@SagaStep` *(semantics changed)* | method | `associationProperty` | Unchanged shape. A step now MUST return `void` (FR-006) and dispatches commands through `CommandGateway`. |

All other annotations (`@Saga`, `@SagaId`, `@StartSaga`, `@EndSaga`, `@SagaCompensation`, `@Command`) are
unchanged. Several `@EndSaga` steps per saga are allowed, as before.

## Author-facing contracts

### `CommandGateway` *(new, `command/`)*

| Operation | Behavior |
|---|---|
| `void send(Object command)` | Runs the single `@CommandHandler` for `command`'s type to completion inside the caller's current unit of work. Handler failures propagate unchanged and mark the unit of work for rollback (FR-012). |

Errors: `IllegalArgumentException` if `command` is `null` or its type is not `@Command`-annotated;
`NoCommandHandlerException` *(new, unchecked, carries the command type)* if no handler exists (FR-011).
Reached from a saga through one optional `CommandGateway`-typed field (at most one per saga, research §1).

### `EventPublisher` *(new, `event/`)*

| Operation | Behavior |
|---|---|
| `void publish(Object domainEvent)` | Injectable, for application code (typically outside a unit of work, e.g. to start a saga). Inside a unit of work it is equivalent to `DomainEvents.record`. Delivery rules of FR-017 (research §5). |

Errors: `IllegalArgumentException` if `domainEvent` is `null` or not `@DomainEvent`-annotated.

### `DomainEvents` *(new static facade, `event/`)*

| Member | Behavior |
|---|---|
| `static void record(Object domainEvent)` | Adds the event to the active unit of work (FR-015). `IllegalArgumentException` if `null` or not `@DomainEvent`; `IllegalStateException` if no unit of work is active. |
| `static List<Object> capture(Runnable action)` | Testing aid: runs `action` with a temporary recorder and returns the recorded events in order. |

No base class or interface is involved; any code on the unit of work's thread can call it (research §4).

### `SagaContext` *(unchanged contract, representation changed)*

Same operations and value rules as feature 001 (`getState`, `putState`, `updateStateAttribute`; scalars and
structured maps; null values rejected). What changes is where values live: in a `SagaContextRecord`, not in
`SagaInstance.state` (FR-024). Values written by a step survive the rollback of that step's unit of work.

## SPI contracts (`spi/`), implemented by the engine host, storage modules, or the application

| Type | Operations | Implemented by |
|---|---|---|
| `AggregateResolver` *(new)* | `boolean supports(Class<?> aggregateType)`; `Object load(Class<?> aggregateType, Object aggregateId)`; `void save(Object aggregate)` | Host integrations only, never client code (Spring starter: adapts the client's Spring Data repositories). Client `@AggregateLoader` / `@AggregateSaver` methods take precedence (FR-013). |
| `DomainEventScope` *(new)* and `DomainEventRecorder` *(new)* | `static void runWith(DomainEventRecorder recorder, Runnable work)`; `void record(Object domainEvent)` | The engine, to bind the recorder `DomainEvents.record` writes to for one unit of work (research §4) |
| `HandlerInstanceProvider` *(new)* | `Object instanceOf(Class<?> handlerType)` | The engine host (Spring starter: application context) |
| `SagaContextStore` *(new)* | `Optional<SagaContextRecord> find(String sagaId)`; `void save(SagaContextRecord record)`; `void delete(String sagaId)` | Storage modules, as a separate table or collection |
| `StorageProvider` *(unchanged)* | `save`, `findById`, `findByAssociation`, `delete` | Storage modules |
| `EventDispatcher` *(unchanged)* | `dispatch`, `registerSagaType`; also used for after-commit delivery (research §5) | Transport modules |

All SPI operations throw `IllegalArgumentException` for `null` arguments, following the existing SPI
convention.

## Persisted types (`spi/`)

### `SagaContextRecord` *(new record)*

| Field | Type | Rule |
|---|---|---|
| `sagaId` | `String` | Non-null; references `SagaInstance.sagaId` (FR-024) |
| `values` | `Map<String, Object>` | Defensive, unmodifiable copy; structured values are nested maps (001 semantics) |
| `lastModifiedAt` | `Instant` | Non-null |

Written outside the saga-scoped unit of work, so it survives a rollback.

### `SagaInstance` *(changed record)*

Existing components keep their meaning: `sagaId`, `sagaType`, `state` (the saga class's own serialized
fields; it no longer holds `SagaContext` values), `associations`, `status`, `createdAt`, `lastModifiedAt`.
New components:

| Field | Type | Rule |
|---|---|---|
| `steps` | `Map<String, StepRecord>` | One entry per `@SagaStep` method name; defensive copy (FR-004) |
| `outcome` | `String`, nullable | Name of the reached `@EndSaga` step; non-null if and only if `status == COMPLETED` (FR-005) |
| `failure` | `SagaFailure`, nullable | Non-null if and only if `status == FAILED` (FR-021) |

`steps`, `outcome` and `status` commit with the unit of work. `failure` and the `FAILED` status are written
after a rollback, in their own write.

### `StepStatus` *(new enum)*: `NOT_RUN`, `COMPLETED`

A step becomes `COMPLETED` only when its method returned, every command it dispatched was handled, and its
unit of work committed. A step in a rolled-back unit of work stays `NOT_RUN`.

### `StepRecord` *(new record)*

| Field | Type | Rule |
|---|---|---|
| `status` | `StepStatus` | Non-null |
| `touchedAggregates` | `List<AggregateReference>` | Defensive copy; empty unless `status == COMPLETED`; in recording order, without duplicates (FR-004) |

### `AggregateReference` *(new record)*

| Field | Type | Rule |
|---|---|---|
| `aggregateType` | `String` | Non-null; fully qualified name of the `@Aggregate` class named by the event's `aggregate` attribute |
| `aggregateId` | `String` | Non-null; the event's `@AggregateIdentifier` value, as `String.valueOf` |

### `SagaFailure` *(new record)*

| Field | Type | Rule |
|---|---|---|
| `stepName` | `String` | Non-null; the step that failed |
| `causeType` | `String` | Non-null; fully qualified class name of the failure |
| `message` | `String`, nullable | The failure's message |
| `failedAt` | `Instant` | Non-null |

### `SagaStatus` *(unchanged enum)*: `CREATED`, `RUNNING`, `COMPENSATING`, `COMPLETED`, `FAILED`

## Status transitions (FR-005, FR-020, FR-021)

```text
start step's unit of work begins  ──►  CREATED  (inside the unit of work only)
unit of work commits at a wait point  ──►  RUNNING
unit of work commits at an @EndSaga step  ──►  COMPLETED  (outcome recorded; terminal)
any unit of work rolls back  ──►  FAILED  (failure recorded; terminal)
COMPENSATING: reserved for the compensation spec, never entered here
```

`CREATED` is never observable outside the first unit of work: that unit of work either commits (`RUNNING` or
`COMPLETED`) or rolls back (`FAILED`).

## `SagaLifecycle` *(changed, engine-facing only)*

| Operation | Status effect | Rejections |
|---|---|---|
| `start(sagaType, associationProperty, associationValue)` *(unchanged)* | → `CREATED`, every step `NOT_RUN` with no touched aggregates | `DuplicateSagaInstanceException` |
| `recordStepCompleted(sagaId, stepName, touchedAggregates)` *(new)* | none; step → `COMPLETED` with its touched aggregates | `InvalidSagaTransitionException` if missing or terminal; `IllegalArgumentException` for an unknown step |
| `advance(sagaId)` *(narrowed)* | `CREATED`/`RUNNING` → `RUNNING` | `InvalidSagaTransitionException` if missing or terminal |
| `complete(sagaId, outcomeStep)` *(replaces `complete(sagaId)`)* | → `COMPLETED`, records `outcome` | `InvalidSagaTransitionException` if missing or terminal |
| `fail(sagaId, failure)` *(new)* | `CREATED`/`RUNNING` → `FAILED`, records `failure` | `InvalidSagaTransitionException` if missing or terminal |

Verifying that `outcomeStep` is an `@EndSaga` step stays the engine's responsibility (research §8).

## Validation (`scanner/`)

| Type | Change |
|---|---|
| `AnnotationScanner` | New `ValidationReport validateAll(Collection<Class<?>> classes)`; `validate(Class<?>)` unchanged |
| `ValidationReport` *(new record)* | `List<SagaValidationResult> classResults`, `List<String> modelErrors`, `boolean isValid()` |
| `SagaValidationResult` | Unchanged shape; now also produced for handler-hosting classes |
| `ReflectiveAnnotationScanner` | Implements the new rules in research §7 |

## Relationship to existing types

- **Feature 001 `SagaContext` / `InMemorySagaContext`**: value semantics unchanged; the double is reused by the
  test harness behind an in-memory `SagaContextStore`.
- **Feature 001 `SagaLifecycle` / `InMemorySagaLifecycle` and their tests**: updated for the changed
  operations above.
- **`SagaInstance` constructor callers** (001 double, `SagaInstanceTest`): updated for the new components.
- **`EventDispatcher`**: unchanged signature; gains a second caller (after-commit delivery).
