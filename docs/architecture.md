# Architecture

This document describes the target design of Saga Forge: what is built today, what is planned, and the
reasoning behind the boundaries between modules. See the root [README.md](../README.md) for the pitch and
[use-cases.md](use-cases.md) for worked examples.

## Status at a glance

| Layer | State |
|---|---|
| Annotation model (`@Saga`, `@SagaId`, `@SagaStep`, `@StartSaga`, `@EndSaga`, `@SagaCompensation`, `@DomainEvent`, `@Command`, `@ReadModel`, `@Aggregate`, `@AggregateIdentifier`, `@CommandHandler`, `@AggregateLoader`, `@AggregateSaver`) | Implemented, tested |
| `AnnotationScanner` / `ReflectiveAnnotationScanner` / `SagaValidationResult` / `validateAll` → `ValidationReport` | Implemented, tested |
| `DomainEvents.record` / `DomainEventScope` (thread-bound event recording) | Implemented, tested |
| `SagaContext` / `SagaLifecycle` (`start`, `recordStepCompleted`, `advance`, `complete(sagaId, outcomeStep)`, `fail`) | Contracts defined and tested via test doubles; no implementation |
| `CommandGateway`, `EventPublisher` | Contracts defined; behavior specified by [contracts/saga-unit-of-work.md](../specs/002-transactional-step-sequence/contracts/saga-unit-of-work.md) and proven by a test-only harness; no engine |
| SPI `AggregateResolver`, `HandlerInstanceProvider`, `SagaContextStore` / `SagaContextRecord` | Contracts defined; behavior specified by [contracts/saga-unit-of-work.md](../specs/002-transactional-step-sequence/contracts/saga-unit-of-work.md) and proven by a test-only harness; no engine |
| `SagaInstance` step records (`StepRecord`: `NOT_RUN`/`COMPLETED` + touched aggregates), `outcome`, `failure` (`SagaFailure`) | Contracts defined; behavior specified by [contracts/saga-unit-of-work.md](../specs/002-transactional-step-sequence/contracts/saga-unit-of-work.md) and proven by a test-only harness; no engine |
| SPI (`StorageProvider`, `EventDispatcher`) | Defined, no implementations |
| Saga engine runtime | Not implemented |
| Storage providers (Postgres, MySQL, MongoDB) | Not implemented |
| Transports (in-memory, Kafka) | Not implemented |
| Spring Boot autoconfiguration | Not implemented |

## Module responsibilities

- **`saga-forge-core`** — owns the annotation model, the scanner that validates and reflects over `@Saga`
  classes, the `SagaContext`/`SagaLifecycle`/`CommandGateway`/`EventPublisher` contracts (interfaces only,
  no implementation), `DomainEvents.record`, and the SPI contracts. Zero runtime dependencies, including no
  Spring dependency. This is deliberate: the annotation model and validation rules must be usable from a
  future non-Spring runtime without forcing a Spring dependency on every consumer.
- **`saga-forge-spring-boot-starter`** — the only module users explicitly add for wiring. Detects, via
  `@ConditionalOnClass`, which storage and transport dependencies are on the classpath, and assembles the
  engine accordingly. There is no `SagaEngineConfig` for a user to hand-build; if autoconfiguration can't
  resolve an unambiguous choice (e.g. two storage providers on the classpath), it should fail fast with a
  clear error rather than guess.
- **`saga-forge-storage-{postgres,mysql,mongodb}`** — each implements `StorageProvider` and
  `SagaContextStore` for one backend.
  Exactly one should be on the classpath per application. These are the only three storage backends in
  scope; adding a fourth is a scope decision, not an implementation detail.
- **`saga-forge-transport-{inmemory,kafka}`** — each implements `EventDispatcher`. In-memory wraps Spring's
  `ApplicationEventPublisher`; Kafka wraps a `KafkaTemplate`/consumer pair. These are the only two
  transports in scope. If neither is on the classpath, the starter falls back to in-memory as the default
  rather than failing, since in-memory requires no external infrastructure.
- **`saga-forge-example`** — a runnable saga (`OrderProcessingSaga`) used as a living spec. It must always
  compile against whatever the annotation model currently supports; if an annotation's contract changes, the
  example changes with it in the same change.

## Autoconfiguration detection matrix (target)

| Classpath signal | Storage provider selected |
|---|---|
| `org.postgresql.Driver` + Postgres JDBC/R2DBC starter | `saga-forge-storage-postgres` |
| MySQL driver | `saga-forge-storage-mysql` |
| MongoDB driver | `saga-forge-storage-mongodb` |
| None of the above | Autoconfiguration fails fast — a saga needs durable storage, so there is no silent fallback |

| Classpath signal | Transport selected |
|---|---|
| `org.apache.kafka.clients.producer.KafkaProducer` | `saga-forge-transport-kafka` |
| Neither Kafka nor any other broker client present | `saga-forge-transport-inmemory` (default) |

Exactly one storage provider and one transport are expected to resolve. Autoconfiguration does not attempt to
support running two storage providers or two transports simultaneously.

## Why orchestration on an event-driven substrate

Steps are triggered by domain events. A step is a `void` method that dispatches zero or more commands through
the saga's `CommandGateway` field; each command runs its single `@CommandHandler` synchronously, and the
handler records the resulting domain events with `DomainEvents.record`. Work is grouped into a **saga-scoped
unit of work** (specified in
[contracts/saga-unit-of-work.md](../specs/002-transactional-step-sequence/contracts/saga-unit-of-work.md)):

- Inside a unit of work, delivery is in-process. An event recorded there that triggers another step of the
  same saga instance is queued and run in the same transaction, without passing through the transport.
- The unit of work commits at the first `@EndSaga` step or at a **wait point** (no queued step left). Only
  then are its buffered events handed to `EventDispatcher` — Spring application events or Kafka records.
- The transport carries events only *between* units of work: an external event (or one published through
  `EventPublisher` outside any unit of work) starts or resumes a saga instance in a new unit of work.

At the transport level this is choreography: no central dispatcher decides what happens next. But every
`@Saga` instance keeps durable process state — its `SagaInstance` record (status, per-step `StepRecord`s,
`outcome`, `failure`) through a `StorageProvider`, and its named business state through `SagaContext`,
persisted as a `SagaContextRecord` via `SagaContextStore`. None of these has a runtime implementation yet. With
that state you can ask "where is this saga, and what has it already done" without replaying every event it
reacted to. That queryable, durable process state is what makes this orchestration rather than pure
choreography — it is layered on top of an event-driven delivery mechanism, not a replacement for one.

This process state is also the intended foundation for **downstream policies**: a policy that needs to know
"has the payment step for this booking already completed" should be able to answer that by reading saga
process state (through a `@ReadModel`-backed repository) rather than re-deriving it from raw event history.

## Truthful state and transaction boundaries

A step is only ever recorded `COMPLETED` if its unit of work committed. The aggregate changes made by the
command handlers a unit of work dispatched, the step records, the saga's status and its outcome commit
together, in the host's local transaction — never as an independent "fire and forget" write afterward.
Buffered events are dispatched only after that commit.

If any step, handler, loader, saver, resolver or provider throws — including a failure a step caught — the
whole unit of work rolls back: its aggregate changes and buffered events are discarded, and the instance is
then written as `FAILED` with a `SagaFailure` in a separate write. `SagaContext` values are the deliberate
exception: they are saved through `SagaContextStore` outside the saga transaction, so they survive the
rollback.

This is inherited directly from production experience in `docs/use-cases.md`: a step recorded as completed
despite its business effect being rolled back is a worse failure mode than the step simply failing, because it
silently breaks the saga's ability to resume or retry correctly.

## Feature gaps

The following are requirements a production-grade saga runtime must satisfy, derived from real multi-step,
multi-aggregate orchestration use cases (see [use-cases.md](use-cases.md)). None of them has a runtime in
`saga-forge-core` today; some now have their contracts defined, as noted per item. They are listed here as the
backlog for the engine/starter work, each phrased as a capability the framework must provide — not as an
implementation prescription.

### Queryable process-level state (highest priority)

Today a step can only see the fields carried on the single event instance it receives. There is no way for a
step to read a value that an *earlier* step in the same saga instance computed or loaded, unless that value
was manually threaded forward through a chain of growing event types — which does not scale past a handful of
steps and forces every event type to carry the full upstream state it never otherwise needs.

The framework must provide a **general, queryable process-state store per saga instance**. The contract
half of this is now **defined** in `saga-forge-core`; the runtime and persistence half is **still open**.

What is defined (contract only, in `core.lifecycle`):

- A `@Saga` class may declare at most one `SagaContext`-typed field (recognized by type, no annotation;
  enforced by the scanner). Through it, a step associates a named value with the current saga instance
  (`context.putState("apartment", apartment)`), and a later step reads it back by name and type
  (`context.getState("apartment", Apartment.class)`). Individual fields of a structured value can be
  updated in place with `context.updateStateAttribute(name, field, value)`.
- Reads are type-safe: a stored value not assignable to the requested type raises
  `SagaStateTypeMismatchException`, and a field update against a scalar value raises
  `NonStructuredStateValueException`. Null values are rejected on write, so an empty `Optional` from
  `getState` always means "never written".
- The representation is settled: named values live in a `SagaContextRecord` (`sagaId`, `values`,
  `lastModifiedAt`), stored through the `SagaContextStore` SPI as a record of its own, separate from the
  `SagaInstance` record. A structured value is a nested `Map<String, Object>`. `SagaInstance.state` holds
  only the saga class's own fields, not `SagaContext` values.
- Context writes happen outside the saga-scoped unit of work: `putState` / `updateStateAttribute` changes are
  saved no later than the end of the step that made them, and a `SagaContextStore.save` never joins the saga
  transaction. Values therefore survive a rollback, and a record may exist for a `FAILED` instance.
- These contracts are exercised only through test-only doubles and the test-only harness; no production
  `SagaContext`, `SagaLifecycle` or `SagaContextStore` implementation ships.

What remains to be built (the runtime/persistence half):

- No engine exists to load a saga's `SagaContextRecord` and populate its `SagaContext` field when a unit of
  work begins, or to save what a step wrote.
- Because different units of work — possibly on a different thread, or a different process entirely if the
  transport is Kafka — cannot share Java object identity or a live `ThreadLocal`, this state must round-trip
  through a concrete `SagaContextStore` between units of work. No `SagaContextStore` or `StorageProvider`
  implementation exists yet, so cross-unit-of-work persistence of `SagaContext` state is unbuilt.
- This is also the mechanism that should back the "process state available to downstream policies" principle
  already stated in the "Why orchestration on an event-driven substrate" section above — a `@ReadModel`-backed
  policy reading saga state should be reading through this same `SagaContext`-backed state, not a parallel
  mechanism.

### Idempotent step execution

A step handling the same event twice (at-least-once delivery, consumer rebalance, manual retry) must not
re-dispatch its commands or re-run its side effect. This requires a uniqueness check — most naturally a unique
constraint on `(saga_id, step_name)` at the storage layer — consulted before a step's handler runs, so a
duplicate dispatch is recognized and skipped rather than re-executed.

A second, coarser-grained idempotency concern also needs coverage: deduplicating the *triggering command*
itself (e.g. two requests to accept the same booking arriving concurrently) before a saga instance is even
created or resumed, not just deduplicating individual step execution within an existing instance.

### Per-step retry policy with failure classification

Each step must be able to declare its own retry policy (max attempts, backoff strategy) because different
steps call different downstream systems with different failure characteristics. Retries must apply only to
transient infrastructure failures (timeouts, connection errors) — never to domain rule violations, which are
terminal and resolve via compensation or intervention (see below). This means the framework needs its own
failure-classification contract (e.g. a marker interface or exception hierarchy a step can throw to signal
"transient" vs. "terminal") that doesn't depend on any particular retry library's exception taxonomy, since
`saga-forge-core` has zero runtime dependencies and the eventual Spring starter should not require step
authors to catch or throw a Spring-specific exception type just to get correct retry behavior.

Retry attributes do not belong on `@SagaStep` as an alias into a third-party retry annotation — they must be
framework-owned primitive data (attempt count, backoff parameters) that the engine itself reads and executes,
so the same `@SagaStep` contract can eventually be honored by a non-Spring runtime. Any addition of retry
attributes to `@SagaStep` must be proposed as a change to `.kiro/specs/saga-forge-framework/design.md` first,
since it changes the annotation's formal contract and validation rules.

Until that design exists, any failure inside a unit of work rolls it back and ends the saga `FAILED` (see
"Truthful state and transaction boundaries"). The retry design will revisit this: a transient failure should
be retried rather than end the saga.

### Transaction boundary relative to the triggering dispatch

This is now **specified** by
[contracts/saga-unit-of-work.md](../specs/002-transactional-step-sequence/contracts/saga-unit-of-work.md): an
event arriving through the transport opens a new saga-scoped unit of work in the host's local transaction;
steps triggered in-process run inside it; it commits at the first `@EndSaga` step or at a wait point, and
rolls back as a whole on any failure. A later unit of work sees only what earlier units of work committed
(plus `SagaContext` values, which are written outside the transaction). At most one unit of work runs per
saga instance at a time.

What remains open is engine work: the transaction SPI through which the engine opens, commits and rolls back
the host's local transaction, and cross-process enforcement of one unit of work per instance (for example an
optimistic version on `SagaInstance`). Both belong to the engine feature, so that storage and transport
implementations do not each decide them inconsistently.

### Failure categorization and automatic compensation dispatch

A step's failure must resolve to exactly one of two outcomes:

- **Compensatable** — a `@SagaCompensation(forStep = "...")` method exists for the failed step (or for a
  prior completed step whose effect must now be undone), and running it returns the aggregate(s) involved to
  a consistent state.
- **Requires intervention** — no compensation applies, or compensation itself cannot proceed safely. The saga
  must be left in a terminal, inspectable failed state (not silently retried forever) so a human can decide
  the next action.

A failed saga is now recorded explicitly: after a rollback, the instance is written as `FAILED` with a
`SagaFailure` (failed step, cause type, message, time), so it is distinguishable from a saga that hasn't run
yet. What is still missing is the split between the two outcomes above — `saga-forge-core` does not model
"compensatable" versus "requires intervention", and every failure ends the saga `FAILED` for now. The
semantics of compensation, including **when compensation is invoked automatically** (which failure
categories, from which step) versus explicitly, belong to a separate spec. Until then, declaring
`@SagaCompensation(forStep = "...")` only makes the compensation *discoverable*, not *automatically wired* to
any particular failure.

## jMolecules consideration

[jMolecules](https://github.com/xmolecules/jmolecules) provides DDD/architecture marker annotations
(`@AggregateRoot`, `@Entity`, `@Service`, etc.) and structural tests that verify a codebase actually respects
them. Saga Forge's own markers (`@DomainEvent`, `@Command`, `@ReadModel`) already play a similar role, but
narrower — they mark types for the scanner, not for architectural verification.

The intended integration, not yet decided or built, is **optional interop, not a hard dependency**:

- `saga-forge-core` should keep its own `@DomainEvent`/`@Command`/`@ReadModel` annotations as the primary
  contract the scanner validates against, since the framework cannot require every consumer to adopt
  jMolecules.
- A thin, separately-versioned adapter could recognize jMolecules' `@Event`, `@Service`-style annotations as
  equivalent, so teams already using jMolecules don't have to double-annotate their types. This is additive
  and should not change `saga-forge-core`'s zero-dependency posture.
- Until this is designed in detail, treat jMolecules support as unimplemented and don't assume any
  recognition of jMolecules annotations exists.

## Vocabulary

Saga Forge borrows its vocabulary from DDD / EventStorming rather than inventing new terms.

- **Command** — an instruction expressing intent to change state, handled by exactly one `@CommandHandler`.
  Dispatched through the command gateway; a step never returns one. A command aimed at an existing aggregate
  carries that aggregate's identity in its `@AggregateIdentifier` field. Marked with `@Command`.
- **Command gateway** — the `CommandGateway` contract that runs a command's single handler synchronously,
  inside the caller's unit of work. A saga reaches it through one optional `CommandGateway`-typed field
  (recognized by type, no annotation); services and application code get it by dependency injection.
- **Command handler** — a `@CommandHandler` method or constructor taking one `@Command` parameter. On an
  `@Aggregate`, an instance method runs on the aggregate loaded by the command's `@AggregateIdentifier` (via a
  client `@AggregateLoader`, else the host's `AggregateResolver`), which is then saved (via `@AggregateSaver`,
  else the resolver, else the client's own unit of work); a constructor or static factory creates a new
  aggregate. On a service, the instance comes from the host's `HandlerInstanceProvider`. Never declared on a
  `@Saga`.
- **Event** (Domain Event) — a fact about one aggregate that already happened. Declares its aggregate with
  `@DomainEvent(aggregate = X.class)` and carries exactly one `@AggregateIdentifier` field. Recorded with the
  static `DomainEvents.record(...)` (no base class or interface), or published with an injectable
  `EventPublisher` outside a unit of work. Client code is responsible for recording an event only after a real
  state transition; Saga Forge does not check this. Saga steps react to events; they never react to commands.
- **Aggregate** — the consistency boundary that owns the state a command changes and the events that record
  that change. Marked with `@Aggregate`, identified by an `@AggregateIdentifier` field. Its persistence is
  owned by client code; Saga Forge never reaches inside an aggregate, it only dispatches commands to its
  handlers and listens for its events.
- **Policy** — the cross-aggregate rule a saga encodes: "when X happens, Y should happen." A `@Saga` class is
  a declarative expression of one or more related policies, scoped by a shared association property.
- **Projection** (Read Model) — a denormalized view built from events, used to enrich a step's decision
  without reaching into another aggregate's internals. Accessed via a repository injected into the saga;
  types exposed this way are marked `@ReadModel`.
- **Saga** — the durable, single-process orchestrator for one instance of a policy, identified by `@SagaId`
  and correlated to events via `associationProperty`. Not to be confused with the Saga pattern in the generic
  distributed-transaction sense — here it specifically means an annotated class matching this framework's
  model.
- **Step** — one unit of saga behavior: a `void` method triggered by one event type, correlated via
  `associationProperty`, that dispatches zero or more commands through the command gateway and may start
  (`@StartSaga`) or end (`@EndSaga`) the saga's lifecycle. Declared with `@SagaStep`.
- **Saga-scoped unit of work** — the transaction in which a triggered step and every step it triggers
  in-process run. It commits at the first `@EndSaga` step or at a wait point; any failure rolls it back and
  leaves the saga `FAILED`. Specified in
  [contracts/saga-unit-of-work.md](../specs/002-transactional-step-sequence/contracts/saga-unit-of-work.md).
- **Outcome** — the `@EndSaga` step a completed saga reached, recorded as `SagaInstance.outcome`. A saga may
  declare several `@EndSaga` steps, one per possible outcome.
- **Compensation** — the declared undo for a step whose business effect must be reversed after a later step
  fails. Declared with `@SagaCompensation(forStep = "...")`, scoped to exactly one step by name. Its runtime
  semantics belong to a separate spec.
