# AGENTS.md

## Repo Reality Check
- Source of truth order: `README.md` (pitch + canonical example) → `docs/architecture.md` (design + vocabulary) →
  `docs/use-cases.md` (worked examples) → actual code in `saga-forge-core`.
- `docs/Saga_Framework_Epic.md`, `docs/Saga_User_Stories.md`, `docs/Saga_Technical_Tasks.md` are historical
  planning artifacts from an earlier naming scheme. They are superseded wherever they conflict with the docs
  above — do not treat them as current design.
- `.kiro/specs/saga-forge-framework/` and `.kiro/specs/saga-context-lifecycle/` hold the formal
  requirements/design specs backing what is actually implemented in `saga-forge-core`. They are accurate and
  worth reading for implementation-level detail (validation rules, SPI contracts, correctness properties).

## Current Status
- Implemented and tested: annotation model, `AnnotationScanner`/`ReflectiveAnnotationScanner`,
  `SagaValidationResult`, SPI contracts (`StorageProvider`, `EventDispatcher`) — all in `saga-forge-core`.
- Contracts only (`core.lifecycle`): `SagaContext` (named, type-safe per-instance business state via
  `getState`/`putState`/`updateStateAttribute`; null values rejected) and `SagaLifecycle`
  (`start`/`advance`/`complete`), plus `SagaStateTypeMismatchException`, `NonStructuredStateValueException`,
  `DuplicateSagaInstanceException`, `InvalidSagaTransitionException`. Tested via test-only doubles
  (`InMemorySagaContext`/`InMemorySagaLifecycle`); no production implementation ships — same posture as
  `EventDispatcher`.
- Contracts only, added by `specs/002-transactional-step-sequence`: `core.command` (`CommandGateway`,
  `NoCommandHandlerException`, `AggregateNotFoundException`), `core.event` (`DomainEvents.record`/`capture`,
  `EventPublisher`), SPI `AggregateResolver`/`AggregateReference`, `HandlerInstanceProvider`,
  `SagaContextStore`/`SagaContextRecord`, `SagaInstance` with per-step `StepRecord`/`StepStatus`, `outcome` and
  `SagaFailure`; `SagaLifecycle` gains `recordStepCompleted`, `complete(sagaId, outcomeStep)` and `fail`. The
  scanner (implemented) gains `validateAll` → `ValidationReport`. Execution semantics are proven only by the
  test-only `SagaTestHarness` in `saga-forge-core/src/test/.../core/execution/harness/` — it is not an engine.
- Implemented and tested against Postgres via Testcontainers (the concurrency suites not yet run, G-003-23), added
  by `specs/003-postgres-saga-persistence`: `saga-forge-storage-postgres` implements
  `StorageProvider` (`PostgresStorageProvider`) and `SagaContextStore` (`PostgresSagaContextStore`) over a
  caller-supplied `DataSource`, with the DDL shipped as the classpath resource `schema.sql`; failures surface as
  `ConcurrentSagaUpdateException` (concurrent diverging save) or `UncheckedSqlException`. `saga-forge-core` gains
  the storage-agnostic, write-only lifecycle-history vocabulary `core.spi.SagaLifecycleEntry` /
  `SagaLifecycleEventKind`, which since 004 is handed to storage through `StorageProvider.save(instance, entries)`.
  004 adapted the module: `PostgresStorageProvider(DataSource, TransactionBoundary)` joins the engine's transaction
  and locks with `findByIdAndLock` (`FOR NO KEY UPDATE`), and `JdbcTransactionBoundary` is a plain-JDBC
  `TransactionBoundary`.
- Implemented and unit-tested over in-memory doubles, added by `specs/004-saga-engine`: the saga engine runtime in
  `saga-forge-engine` (`SagaEngine`, an `EventReceiver`): event routing by association, the saga-scoped unit of work
  in the host's local transaction, the `FAILED` write, one unit of work per instance (in-process lock plus
  `findByIdAndLock`), the outbox-ready `EventHandoff` (`AFTER_COMMIT` default, `IN_TRANSACTION`), reentrancy
  rejection and fail-fast startup validation. Production implementations of `SagaContext`, `SagaLifecycle`,
  `CommandGateway` and `EventPublisher` live there, package-private. The 002 harness stays as a test-only reference
  the engine is compared against.
- Implemented and unit-tested, added by `specs/005-step-retry`: step retry and idempotent command processing.
  `@SagaStep(retry = @RetryPolicy(...))` (scanner rule R19, Property 29); core `core.failure` markers
  `TransientFailure`/`FinalFailure` and `FailureClassification` (`TRANSIENT`, `FINAL`, `EXHAUSTED`,
  `UNCLASSIFIED`); `core.command.CommandId`, supplied to a `@CommandHandler` that declares a `CommandId` parameter;
  `HandlingOutcome.DUPLICATE` for an event of an already completed step; `SagaInstance.pendingRetry`,
  `StepRecord.attempts`/`handledCommands`, `SagaFailure.classification`/`attempts`, lifecycle kind `ATTEMPT_FAILED`.
  The engine's `AttemptLoop` re-runs a unit of work after an in-process, bounded wait (`RetryPause`,
  `maxTotalRetryBackoff`, default 30 s). Postgres persists the new fields; existing databases apply
  `schema-upgrade-005.sql`.
- Container suites are fenced by `@Tag("container")` and run only with `-Pcontainer-tests`; the 004 container
  tests have not been run yet (G-003-23).
- Not implemented: MySQL and MongoDB storage, both transports (no `EventDispatcher` implementation), and Spring Boot
  autoconfiguration. The `saga-forge-storage-mysql`, `saga-forge-storage-mongodb`,
  `saga-forge-transport-*`, and `saga-forge-spring-boot-starter` modules exist only as placeholder POMs.
- Do not assume engine/autoconfiguration behavior exists just because a module directory exists. Check for
  actual `.java` source before relying on it.

## Module Map
- `saga-forge-core` — annotations (`@Saga`, `@SagaId`, `@SagaStep`, `@StartSaga`, `@EndSaga`,
  `@SagaCompensation`, `@DomainEvent`, `@Command`, `@CommandHandler`, `@Aggregate`, `@AggregateIdentifier`,
  `@AggregateLoader`, `@AggregateSaver`), scanner, `SagaContext`/`SagaLifecycle` contracts, `CommandGateway`,
  `DomainEvents`/`EventPublisher`, SPI. Zero dependencies.
- `saga-forge-engine` — the saga runtime; depends on the JDK and `saga-forge-core` only. Public surface:
  `SagaEngine` with its host-facing builder, `StartupReport`, `SagaEngineStartupException`,
  `DispatchingEventHandoff`; everything else is package-private in `engine.internal`. Every contract it needs lives
  in `core.spi` (`EventReceiver`, `TransactionBoundary`, `EventHandoff`, `HandlerParameterResolver`,
  `SagaObjectProvider`, ...). Its test-jar ships the in-memory doubles (`InMemoryTransactionBoundary`,
  `TransactionalInMemoryStorageProvider`, `TestTransport`, ...).
- `saga-forge-spring-boot-starter` — target: autoconfiguration that detects an available datastore and
  transport on the classpath and wires the engine. No `SagaEngineConfig` for users to hand-assemble.
- `saga-forge-storage-postgres` — `StorageProvider` + `SagaContextStore` over plain JDBC, with Jackson 3
  (`tools.jackson.core:jackson-databind`) encoding the JSONB columns; also persists lifecycle history
  (`saga_lifecycle_history`). Jackson and the driver stay in this module, never in core.
- `saga-forge-storage-mysql` / `-mongodb` — target: `StorageProvider` + `SagaContextStore` implementations, one
  per backend.
- `saga-forge-transport-inmemory` — target: `EventDispatcher` over Spring `ApplicationEventPublisher`.
- `saga-forge-transport-kafka` — target: `EventDispatcher` over Kafka. These two transports are the only ones
  in scope — do not add RabbitMQ, SQS, etc. without an explicit decision to expand scope.
- `saga-forge-example` — `OrderProcessingSaga` is the canonical reference pattern; keep it in sync with
  whatever the annotation model currently supports.

## Saga Model (Project-Specific)
- Sagas are class-based and annotation-driven: `@Saga` on the class, `@SagaId` on exactly one `String` field.
- Optional business state: at most one `SagaContext`-typed field (recognized by type, no annotation) —
  enforced by the scanner (Property 9 in `.kiro/specs/saga-forge-framework/design.md`).
- Steps are event handlers: `@SagaStep(associationProperty = "...")` on a method taking exactly one
  `@DomainEvent`-annotated parameter and returning `void` (Property 10); no two steps take the same event type
  (Property 11). Steps run in event order, not declaration order.
- Steps dispatch zero or more commands via `commandGateway.send(...)` on an optional `CommandGateway` field
  (recognized by type, no annotation, at most one — Property 13), like the `SagaContext` field.
- Lifecycle boundaries: exactly one `@StartSaga`, one or more `@EndSaga` steps (each a named outcome; the
  instance records which one ended it); both must co-occur with `@SagaStep` on the same method.
- `@CommandHandler` hosts: `@Aggregate` instance methods (target loaded by the command's `@AggregateIdentifier`
  via a client `@AggregateLoader` or a host `AggregateResolver`, saved via `@AggregateSaver`/resolver/client
  UoW), `@Aggregate` constructors/static factories (creation), or plain service methods — never on a `@Saga`
  (Property 12). Exactly one `@Command` param per handler; one handler per command type (Properties 14–15).
- Events: `@DomainEvent(aggregate = X.class)` where `X` is `@Aggregate`, with exactly one
  `@AggregateIdentifier` field (Properties 16–17). Handlers record them with static
  `DomainEvents.record(...)` (no base class/interface on aggregates); outside a unit of work, publish via
  `EventPublisher`.
- Saga-scoped unit of work: commits at the first `@EndSaga` step or at a wait point (no queued event left for
  the instance); any failure rolls the whole unit of work back and the saga is `FAILED`.
  `SagaContext` values live in a separate `SagaContextRecord` and survive the rollback.
- Write order (decided, `specs/003-postgres-saga-persistence/research.md` §8): a `SagaContextRecord` is saved
  only after its `SagaInstance` row exists. The engine's unit of work persists the instance with
  `StorageProvider.save` before the first `SagaContextStore.save` for that `sagaId`; a context save for an
  unknown `sagaId` fails with `UncheckedSqlException` (FK violation). 002's harness does not follow this yet
  (G-ENG-01).
- Validate a whole model with `AnnotationScanner.validateAll(...)` → `ValidationReport` (cross-class rules,
  Properties 14–20); `validate(...)` covers single-saga rules only.
- Compensation is step-targeted: `@SagaCompensation(forStep = "...")`, where `forStep` must name an existing
  `@SagaStep` method on the same class.
- Canonical example: `saga-forge-example`'s `OrderProcessingSaga` (`handleOrderCreated` (`@StartSaga`) →
  `processPayment` → `completeOrder` (`@EndSaga`), all on `associationProperty = "orderId"`):
  `ProcessPaymentCommand` handled by `PaymentService` (service), `ReserveStockCommand` handled by `Order`
  (aggregate instance method, loaded/saved through `OrderRepository`); `compensatePayment` mapped to
  `processPayment`.
- Transport is event-driven (choreography-shaped at delivery); the saga instance keeps durable,
  orchestration-shaped process state per `docs/architecture.md`. Don't conflate the two — the SPI contracts are
  transport-agnostic by design, so don't leak Kafka- or in-memory-specific assumptions into `saga-forge-core`.

## Spec-Driven Development (Speckit)
- saga-forge uses GitHub Speckit for all *new* feature work, wired in via Claude Skills under
  `.claude/skills/speckit-*`: `/speckit-constitution`, `/speckit-specify`, `/speckit-plan`, `/speckit-tasks`,
  `/speckit-implement`, `/speckit-converge` (core flow), plus `/speckit-clarify`, `/speckit-analyze`,
  `/speckit-checklist` (enhancement skills).
- New features follow `specs/NNN-feature-name/` (spec.md → plan.md → tasks.md, plus research.md/data-model.md/
  quickstart.md/contracts/ as needed), correlated to a same-named git branch. Start with `/speckit-specify`.
- `.kiro/specs/saga-forge-framework/` and `.kiro/specs/saga-context-lifecycle/` are the pre-Speckit historical
  baseline documenting what's already implemented (see "Repo Reality Check" above). They are not being
  migrated or regenerated into the `specs/NNN-*` convention — leave them as-is as accurate historical record.
- `.specify/memory/constitution.md` is ratified and defines governing principles I–V (Zero-Dependency
  Core, Annotation-Driven Declarative Style, Formal Spec Conformance, Explicit Narrow Scope, Documentation
  Hierarchy as Source of Truth). Plans and tasks cite them as "Constitution Principle N"; amend it via
  `/speckit-constitution`.

## Known Gaps to Respect When Extending
- `.specify/memory/memory.md` is the register of known gaps, deferred decisions and limitations (IDs such as
  `G-003-01`); check it before extending a feature.
- See `docs/architecture.md#feature-gaps` for the full backlog: queryable process-level state (highest
  priority), transaction boundary relative to the triggering dispatch, and failure categorization with
  compensation dispatch (`specs/006-saga-compensation`, specified, builds on 005's classification).
- Retry attributes live on `@SagaStep(retry = @RetryPolicy(...))`. Change them only together with
  `.kiro/specs/saga-forge-framework/design.md` (R19, Property 29); keep them framework-owned, never an alias of a
  third-party retry annotation. A handler that calls an outside system takes a `CommandId` parameter and passes
  `commandId.value()` on as the idempotency key.
- Retry and idempotency limitations, see memory.md G-005-01 to G-005-07: no long or scheduled backoff (G-005-01); a
  waiting retry blocks the caller and holds the instance (G-005-02); outside systems are safe only if handlers pass
  the identity on (G-005-03); the triggering request is not deduplicated before a saga exists (G-005-04); a crash
  during a fresh event's first attempt is not counted (G-005-05); a retry of a start unit of work does not resume
  after a crash (G-005-06).
- No compensation-vs-intervention-required outcome categorization exists in code yet.
- `SagaContext` values are a separate `SagaContextRecord` behind `SagaContextStore`; the engine leaves
  `SagaInstance.state` empty and treats other saga fields as dependencies (G-ENG-06, use-or-drop still open).
- Engine limitations, see memory.md G-ENG-05 to G-ENG-13: a crash between the creation write and the first unit of
  work strands a `CREATED` instance (G-ENG-05); the default handoff is at most once, an outbox needs
  `IN_TRANSACTION` (G-ENG-07); duplicate starts are caught across processes only after the creation write committed
  (G-ENG-08); the `FAILED` write can mark an instance another process moved on (G-ENG-10); the Postgres
  `FOR NO KEY UPDATE` reasoning is unverified against a database (G-ENG-11); `begin()` suspending vs. rejecting a
  host transaction is boundary-specific (G-ENG-12).
- Deferred to the Spring starter: a Spring Data repository adapter behind `AggregateResolver`.
- Compensation semantics (when/how `@SagaCompensation` runs) belong to a separate spec; a `FAILED` saga's
  committed effects currently stay in place.
- A retried attempt sees the `SagaContext` values earlier attempts wrote (they live outside the transaction); steps
  should write them so that writing again is harmless.
- Deferred: whether a `@StartSaga` step may also be a `@CommandHandler`. Dropped for now: programmatic abort
  (e.g. `SagaLifecycle.abort()`) — don't add it without a spec.
- No waiting timeouts: a saga waits indefinitely for its next event; deadlines stay a separate scheduled
  process.
- jMolecules integration is a documented consideration (`docs/architecture.md`), not yet decided or built.
- Postgres storage (003), see memory.md for detail: concurrency is a row lock plus a forward-transition check,
  with no version field (G-003-02, -03, -13, -19 to -22; G-003-01/-02 explicitly deferred); steps completed in one
  `save()` are ordered by a step-name tiebreak (G-003-04, -16); no lifecycle-history query API, FR-012
  (G-003-24); the host applies `schema.sql`, no migration tool (G-003-25); `DataSource`/pool wiring belongs to
  the starter (G-003-26).

## Agent Working Rules For This Repo
- Prefer edits that preserve the annotation-driven declarative style over imperative orchestration code.
- Keep compensation logic explicit and mapped to concrete step names via `forStep`.
- Never show a step returning a command; steps dispatch through `CommandGateway`.
- Event-driven aggregates/handlers record events via `DomainEvents.record`; don't require a Saga Forge
  supertype on aggregates.
- When adding examples or docs, always show `associationProperty` and the start/end lifecycle annotations.
- Never introduce a storage or transport beyond Postgres/MySQL/MongoDB and Kafka/in-memory without raising it
  with the user first — this is an explicit scope boundary, not an oversight.
- Validate changes against the correctness properties already specified in
  `.kiro/specs/saga-forge-framework/design.md` and `.kiro/specs/saga-context-lifecycle/design.md` before
  changing annotation semantics.
- Follow the DDD vocabulary consistently: Command, Domain Event, Aggregate, Policy, Projection/Read Model,
  Saga, Step, Compensation — see `docs/architecture.md#vocabulary` for definitions.
- Whenever you notice a gap, limitation or deferred decision, record it in `.specify/memory/memory.md` with an
  ID; don't silently fix or ignore it.

## Fast Discovery Checklist
- Read `README.md` for the pitch and the canonical example.
- Read `docs/architecture.md` for module responsibilities, autoconfiguration detection matrix, and vocabulary.
- Read `docs/use-cases.md` for worked multi-step examples including failure/retry/compensation.
- Cross-check any annotation claim against the actual source in `saga-forge-core` before trusting a doc.
