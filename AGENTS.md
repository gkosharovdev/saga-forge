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
- Implemented and tested against Postgres via Testcontainers (all container suites ran green on 2026-11-09), added
  by `specs/003-postgres-saga-persistence`: `saga-forge-storage-postgres` implements
  `StorageProvider` (`PostgresStorageProvider`) and `SagaContextStore` (`PostgresSagaContextStore`) over a
  caller-supplied `DataSource`, with the DDL shipped as the classpath resource `schema.sql`; failures surface as
  `ConcurrentSagaUpdateException` (concurrent diverging save) or `UncheckedSqlException`. `saga-forge-core` gains
  the storage-agnostic, write-only lifecycle-history vocabulary `core.spi.SagaLifecycleEntry` /
  `SagaLifecycleEventKind`, which since 004 is handed to storage through `StorageProvider.save(instance, entries)`.
  004 adapted the module: `PostgresStorageProvider(DataSource, TransactionBoundary)` joins the runtime's transaction
  and locks with `findByIdAndLock` (`FOR NO KEY UPDATE`), and `JdbcTransactionBoundary` is a plain-JDBC
  `TransactionBoundary`.
- Implemented and unit-tested over in-memory doubles, added by `specs/004-saga-engine`: the saga runtime in
  `saga-forge-runtime` (`SagaRuntime`, an `EventReceiver`): event routing by association, the saga-scoped unit of work
  in the host's local transaction, the `FAILED` write, one unit of work per instance (in-process lock plus
  `findByIdAndLock`), the `Outbox` SPI (named `EventHandoff` until 007; `AFTER_COMMIT` default `ImmediateDispatchOutbox`, `IN_TRANSACTION`), reentrancy
  rejection and fail-fast startup validation. Production implementations of `SagaContext`, `SagaLifecycle`,
  `CommandGateway` and `EventPublisher` live there, package-private. The 002 harness stays as a test-only reference
  the runtime is compared against.
- Implemented and unit-tested, added by `specs/005-step-retry`: step retry and idempotent command processing.
  `@SagaStep(retry = @RetryPolicy(...))` (scanner rule R19, Property 29); core `core.failure` markers
  `TransientFailure`/`FinalFailure` and `FailureClassification` (`TRANSIENT`, `FINAL`, `EXHAUSTED`,
  `UNCLASSIFIED`); `core.command.CommandId`, supplied to a `@CommandHandler` that declares a `CommandId` parameter;
  `HandlingOutcome.DUPLICATE` for an event of an already completed step; `SagaInstance.pendingRetry`,
  `StepRecord.attempts`/`handledCommands`, `SagaFailure.classification`/`attempts`, lifecycle kind `ATTEMPT_FAILED`.
  The runtime's `AttemptLoop` re-runs a unit of work after an in-process, bounded wait (`RetryPause`,
  `maxTotalRetryBackoff`, default 30 s). Postgres persists the new fields; existing databases apply
  `schema-upgrade-005.sql`.
- Implemented and tested, added by `specs/006-saga-compensation`: compensation. `@StartCompensation` marks a
  business-failure step (with `@SagaStep`; scanner rules R20 to R22, Properties 30 to 32); a `@SagaCompensation`
  method returns `void` and takes no parameter or one `core.compensation.CompensationTrigger`. The runtime compensates
  every other `COMPLETED` step, most recently completed first (`StepRecord.completionSequence`), each in its own unit
  of work (`CompensationRun`/`CompensationUnitOfWork`), after a `@StartCompensation` step commits or a `FINAL`/
  `EXHAUSTED` failure once a step committed (`CompensationStartWrite`); an `UNCLASSIFIED` failure, or one with nothing
  committed, still ends `FAILED`. New terminal statuses `COMPENSATED` and `REQUIRES_INTERVENTION` (a failed
  compensation, never retried); `SagaInstance.compensationTrigger`, `StepRecord.compensation` (`StepCompensation`),
  five lifecycle kinds; any event for a `COMPENSATING` instance resumes compensation (checked before the duplicate
  check). Postgres persists it; existing databases apply `schema-upgrade-006.sql`.
- Implemented and tested, added by `specs/007-in-memory-transport`: the in-memory transport. The 004 handoff SPI is
  renamed to outbox vocabulary (`EventHandoff` → `Outbox`, `handOff` → `store`, `HandedOffEvents` →
  `OutgoingMessages`, `DispatchingEventHandoff` → `ImmediateDispatchOutbox`, `Builder.handoff` → `outbox`,
  `handoffFailures` → `outboxFailures`). Core gains the storage-agnostic `core.spi.store.MessageStore` (append in the
  unit of work's transaction, leases with expiry, consumed/set-aside, queries, retention), `Outbox.committed` and
  `supportsCommands`, `OutgoingMessages.commands`, and outbox commands: `@Command(delivery = CommandDelivery.OUTBOX)`
  (rule R23, Property 35) stored with the sending unit of work and handled after it commits through the runtime's
  `CommandReceiver`, with the `CommandId` assigned at send time. `saga-forge-transport-inmemory` (core-only) is an
  `IN_TRANSACTION` outbox plus an in-process relay: at least once, per-aggregate order, backoff and set-aside,
  restart recovery, operator queries. `PostgresMessageStore` stores the log (`saga_message_store`; existing databases
  apply `schema-upgrade-007.sql`). The starter carries only this transport's Spring wiring, including Spring
  Modulith interop (one outbox: messages in Modulith's registry, Saga Forge's delivery records beside them).
- Container suites are fenced by `@Tag("container")` and run only with `-Pcontainer-tests`; they all passed on
  2026-11-09 (186 tests), closing G-003-23. The 007 container suites (Postgres log contract, fault injection,
  10k-message restart, Spring own-log and Spring Modulith interop acceptance) passed on 2026-11-11. The whole build
  with the 008 container suites (starter and Spring example) passed on 2026-11-12 (1,502 tests). With 009 (the fixture,
  the interceptor and the example fixture tests) the whole build, container suites included, passed on 2026-11-12
  (1,574 tests). With 010 (OpenTelemetry instrumentation) the whole build, container suites included, passed on
  2026-11-12 (1,612 tests; the opt-in `TelemetryOverheadTest` skipped).
- Implemented and tested, including container suites, added by `specs/008-spring-boot-starter`: the Spring Boot
  starter assembles and starts the runtime with no host code. It collects the model (auto-configuration packages,
  beans, `saga-forge.scan-packages`, `SagaModelContributor`), detects storage (`saga-forge.storage.type` when
  ambiguous), builds Postgres storage over the `DataSource`, backs the host SPIs with Spring (`SpringSagaObjectProvider`:
  prototype saga beans or the no-argument constructor; `SpringHandlerInstanceProvider`;
  `SpringHandlerParameterResolver`; `SpringDataAggregateResolver`), and starts the runtime in `SagaRuntimeLifecycle`
  one phase before `TransportLifecycle`. Misconfigurations fail through `FailureAnalyzer`s. Every bean backs off for an
  application bean. Core gains the additive `OutgoingMessages.recordedEvents` and `Outbox.observesRecordedEvents()`;
  the starter's `ApplicationEventPublishingOutbox` publishes recorded events to Spring inside the unit of work
  (`saga-forge.application-events.mode`, replacing 007's `publish-application-events`), so Spring Modulith's
  `@ApplicationModuleListener`s receive saga events. Optional `sagaforge` Actuator endpoint and health indicator.
  `saga-forge-example-spring` is the canonical saga as a Spring Boot app. The starter's README documents defaults,
  properties and overrides; `ReadmePropertiesTest` keeps it in step with the configuration metadata.
- Implemented and tested, added by `specs/009-saga-test-fixture`: the saga test fixture `saga-forge-test` (core and
  runtime only). `SagaFixture` (given/when/then, `Then` expectations, `EventResult`, `InstanceView`,
  `RecordedCommand`, `FixtureClock`, `SagaExpectationError`) starts the shipped runtime over package-private
  in-memory plug-ins: transactions with snapshots, storage, a context store with the durable value check
  (`DurableContextValues`, `UnsupportedContextValueException`, FR-019), an aggregate store/resolver, a loop-back
  `IN_TRANSACTION` outbox and dispatcher. Record-and-answer by default, `handlers(...)` for real handlers; failure
  rules; retry waits advance the clock; ids `saga-N`/`uow-N`. Core gains the additive `core.spi.CommandInterceptor`/
  `CommandInvocation` and `SagaRuntime.Builder.commandInterceptor` (Property 36), called once per command handling.
  `FixtureParityTest` compares the fixture with a hand-assembled runtime; `DurableContextValueContractTest` (Postgres)
  ties the value rule to `JsonCodec`. Both examples ship fixture tests; the canonical saga now keeps its amount as a
  `String` in `SagaContext`, like the Spring example.
- Implemented and tested, including container suites, added by `specs/010-otel-observability`: OpenTelemetry
  instrumentation. `saga-forge-runtime` and `saga-forge-transport-inmemory` depend on `opentelemetry-api` (1.62.0, the
  version Spring Boot 4.1 manages) and instrument themselves, no-op until the host passes an SDK through
  `SagaRuntime.Builder.openTelemetry`/`InMemoryTransport.Builder.openTelemetry` (plus `telemetry(SagaTelemetrySettings)`,
  a plain value in core's `core.telemetry`). Spans `saga_forge.handle`/`attempt`/`step`/`command`/`compensation`/
  `delivery`/`event.ignored` (`runtime.internal.SagaInstrumentation`, `transport.inmemory.internal.TransportInstrumentation`);
  duration histograms, outcome and retry counters, message-store gauges; diagnostics carry `traceId`/`spanId`.
  Core's `NewMessage`/`StoredMessage` gain `traceContext`; Postgres stores it in `saga_message_store.trace_context`
  (existing databases apply `schema-upgrade-010.sql`). Property 37. The starter hands the application's `OpenTelemetry`
  bean on (`SagaForgeTelemetryConfiguration`, `SagaForgeTelemetry`, `saga-forge.telemetry.*`); the Spring example
  proves it through Micrometer Tracing's OpenTelemetry bridge. `SagaFixture.Builder.openTelemetry` for fixture tests.
  No own observation SPI and no Micrometer instrumentation (G-010-06).
- Released to Maven Central (memory.md DEC-REL-01): Apache-2.0, `release` profile in the root POM, `release.yml`
  publishes on a `vX.Y.Z` tag. Published: core, runtime, test, transport-inmemory, storage-postgres, starter (plus the
  test jars of core, runtime and the transport). Keep the release build green: `mvn -P release -Dgpg.skip -DskipTests
  install` fails on Javadoc errors (no `<h3>` in type comments, no `@throws` on record comments).
- Not implemented: MySQL and MongoDB storage, the Kafka transport; no Quarkus extension yet (G-007-01). The `saga-forge-storage-mysql`,
  `saga-forge-storage-mongodb` and `saga-forge-transport-kafka` modules exist only as placeholder POMs.
- Do not assume engine/autoconfiguration behavior exists just because a module directory exists. Check for
  actual `.java` source before relying on it.

## Module Map
- `saga-forge-core` — annotations (`@Saga`, `@SagaId`, `@SagaStep`, `@StartSaga`, `@EndSaga`,
  `@SagaCompensation`, `@DomainEvent`, `@Command`, `@CommandHandler`, `@Aggregate`, `@AggregateIdentifier`,
  `@AggregateLoader`, `@AggregateSaver`), scanner, `SagaContext`/`SagaLifecycle` contracts, `CommandGateway`,
  `DomainEvents`/`EventPublisher`, SPI. Zero dependencies.
- `saga-forge-runtime` — the saga runtime; depends on the JDK, `saga-forge-core` and `opentelemetry-api` (010) only. Public surface:
  `SagaRuntime` with its host-facing builder, `StartupReport`, `SagaRuntimeStartupException`,
  `ImmediateDispatchOutbox`; everything else is package-private in `engine.internal`. Every contract it needs lives
  in `core.spi` (`EventReceiver`, `TransactionBoundary`, `Outbox`, `HandlerParameterResolver`,
  `SagaObjectProvider`, ...). Its test-jar ships the in-memory doubles (`InMemoryTransactionBoundary`,
  `TransactionalInMemoryStorageProvider`, `TestTransport`, ...).
- `saga-forge-spring-boot-starter` — auto-configuration (008), see its `README.md`: `SagaRuntimeConfiguration`
  (`SagaModelCollector`, the runtime, `CommandGateway`/`EventPublisher` beans, `SagaRuntimeLifecycle`),
  `SpringTransactionConfiguration`, `PostgresStorageConfiguration` (`SchemaInitializer`), `SpringHostConfiguration`,
  `ApplicationEventsConfiguration`, `SagaForgeActuatorConfiguration`, the failure analyzers, `SagaForgeProperties`;
  plus 007's transport wiring (`InMemoryTransportConfiguration`, `TransportLifecycle`, `EventReceiverCustomizer`,
  `ApplicationEventBridge`, `ModulithInteropConfiguration`/`ModulithMessageStore`). No `SagaRuntimeConfig` for users
  to hand-assemble. Spring code stays in this module and `saga-forge-example-spring`.
- `saga-forge-storage-postgres` — `StorageProvider` + `SagaContextStore` over plain JDBC, with Jackson 3
  (`tools.jackson.core:jackson-databind`) encoding the JSONB columns; also persists lifecycle history
  (`saga_lifecycle_history`). Jackson and the driver stay in this module, never in core.
- `saga-forge-storage-mysql` / `-mongodb` — target: `StorageProvider` + `SagaContextStore` implementations, one
  per backend.
- `saga-forge-transport-inmemory` — `InMemoryTransport`: `EventDispatcher` and `IN_TRANSACTION` `Outbox` over a
  `MessageStore`, plus the in-process relay; depends on `saga-forge-core` and `opentelemetry-api` (010) only (no Spring,
  no CDI). Its test-jar ships
  `InMemoryMessageStore`, `MessageStoreContract` and `TransportAcceptanceSuite`.
- `saga-forge-transport-kafka` — target: `EventDispatcher` over Kafka. These two transports are the only ones
  in scope — do not add RabbitMQ, SQS, etc. without an explicit decision to expand scope.
- `saga-forge-test` — the saga test fixture (009), test scope for applications; depends on `saga-forge-core` and
  `saga-forge-runtime` only (no Spring, storage or transport). Public API in `io.github.gkosharovdev.sagaforge.test`;
  its in-memory plug-ins are package-private there. See its `README.md`.
- `saga-forge-example` — `OrderProcessingSaga` is the canonical reference pattern; keep it in sync with
  whatever the annotation model currently supports.
- `saga-forge-example-spring` — the canonical saga as a Spring Boot application (Spring Data JDBC `Order`, service
  beans, no assembly code). Keep it in step with `saga-forge-example`; it keeps amounts as `String` in `SagaContext`
  (G-003-07).

## Saga Model (Project-Specific)
- Sagas are class-based and annotation-driven: `@Saga` on the class, `@SagaId` on exactly one `String` field.
- Optional business state: at most one `SagaContext`-typed field (recognized by type, no annotation) —
  enforced by the scanner (Property 9 in `.kiro/specs/saga-forge-framework/design.md`).
- Steps are event handlers: `@SagaStep(associationProperty = "...")` on a method taking exactly one
  `@DomainEvent`-annotated parameter and returning `void` (Property 10); no two steps take the same event type
  (Property 11). Steps run in event order, not declaration order.
- Steps dispatch zero or more commands via `commandGateway.send(...)` on an optional `CommandGateway` field
  (recognized by type, no annotation, at most one — Property 13), like the `SagaContext` field. A command runs in
  the sender's unit of work unless its type is `@Command(delivery = CommandDelivery.OUTBOX)` (007): then it is stored
  in the outbox and handled after the sender commits, so its failure no longer rolls the step back.
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
- Saga-scoped unit of work: commits at the first `@EndSaga` step, at the first `@StartCompensation` step, or at a
  wait point (no queued event left for the instance); any failure rolls the whole unit of work back, and the saga is
  `FAILED`, or compensates if the failure is `FINAL`/`EXHAUSTED` and an earlier unit of work committed.
  `SagaContext` values live in a separate `SagaContextRecord` and survive the rollback.
- Write order (decided, `specs/003-postgres-saga-persistence/research.md` §8): a `SagaContextRecord` is saved
  only after its `SagaInstance` row exists. The runtime's unit of work persists the instance with
  `StorageProvider.save` before the first `SagaContextStore.save` for that `sagaId`; a context save for an
  unknown `sagaId` fails with `UncheckedSqlException` (FK violation). 002's harness does not follow this yet
  (G-ENG-01).
- Validate a whole model with `AnnotationScanner.validateAll(...)` → `ValidationReport` (cross-class rules,
  Properties 14–20); `validate(...)` covers single-saga rules only.
- Compensation is step-targeted: `@SagaCompensation(forStep = "...")`, where `forStep` must name an existing
  `@SagaStep` method on the same class (not a `@StartCompensation` step), at most one per step. A business failure is
  a `@StartCompensation` step (with `@SagaStep`, never with `@StartSaga`/`@EndSaga`); its name is the recorded
  failure outcome.
- Canonical example: `saga-forge-example`'s `OrderProcessingSaga` (fixture tests in `OrderProcessingSagaFixtureTest`; `handleOrderCreated` (`@StartSaga`) →
  `processPayment` → `completeOrder` (`@EndSaga`), or `rejectOrder` (`@StartCompensation`, on
  `StockUnavailableEvent`, which `Order` records when out of stock), all on `associationProperty = "orderId"`):
  `ProcessPaymentCommand` handled by `PaymentService` (service), `ReserveStockCommand` handled by `Order`
  (aggregate instance method, loaded/saved through `OrderRepository`); `compensatePayment(CompensationTrigger)` mapped
  to `processPayment` sends `RefundPaymentCommand`, handled by `PaymentService` with its `CommandId` as the key.
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
  compensation dispatch (implemented by `specs/006-saga-compensation`).
- Retry attributes live on `@SagaStep(retry = @RetryPolicy(...))`. Change them only together with
  `.kiro/specs/saga-forge-framework/design.md` (R19, Property 29); keep them framework-owned, never an alias of a
  third-party retry annotation. A handler that calls an outside system takes a `CommandId` parameter and passes
  `commandId.value()` on as the idempotency key.
- Retry and idempotency limitations, see memory.md G-005-01 to G-005-07: no long or scheduled backoff (G-005-01); a
  waiting retry blocks the caller and holds the instance (G-005-02); outside systems are safe only if handlers pass
  the identity on (G-005-03); the triggering request is not deduplicated before a saga exists (G-005-04); a crash
  during a fresh event's first attempt is not counted (G-005-05); a retry of a start unit of work does not resume
  after a crash (G-005-06).
- Compensation limitations, see memory.md G-006-02 to G-006-09: no confirmation wait for an undo (G-006-02); no
  operator path out of `REQUIRES_INTERVENTION` (G-006-03); no "irreversible step" marker (G-006-04); a `COMPENSATING`
  instance with no further event never resumes (G-006-05); a compensation sees the trigger's event type, not the
  event (G-006-06); upgraded instances may get a name-ordered `completionSequence` (G-006-07); compensations block the
  caller and hold the instance (G-006-08); a failed compensation is never retried (G-006-09). A compensation
  interrupted before its commit runs again on resume, so its commands must be idempotent (pass `CommandId` on).
- `SagaContext` values are a separate `SagaContextRecord` behind `SagaContextStore`; the runtime leaves
  `SagaInstance.state` empty and treats other saga fields as dependencies (G-ENG-06, use-or-drop still open).
- Engine limitations, see memory.md G-ENG-05 to G-ENG-13: a crash between the creation write and the first unit of
  work strands a `CREATED` instance (G-ENG-05); the default `ImmediateDispatchOutbox` is at most once, an outbox needs
  `IN_TRANSACTION` (G-ENG-07); duplicate starts are caught across processes only after the creation write committed
  (G-ENG-08); the `FAILED` write can mark an instance another process moved on (G-ENG-10); the Postgres
  `FOR NO KEY UPDATE` reasoning is unverified against a database (G-ENG-11); `begin()` suspending vs. rejecting a
  host transaction is boundary-specific (G-ENG-12).
- A `FAILED` saga (an `UNCLASSIFIED` failure) keeps its committed effects in place; only `FINAL`/`EXHAUSTED`
  failures and `@StartCompensation` steps compensate.
- A retried attempt sees the `SagaContext` values earlier attempts wrote (they live outside the transaction); steps
  should write them so that writing again is harmless.
- Deferred: whether a `@StartSaga` step may also be a `@CommandHandler`. Dropped for now: programmatic abort
  (e.g. `SagaLifecycle.abort()`) — don't add it without a spec.
- No waiting timeouts: a saga waits indefinitely for its next event; deadlines stay a separate scheduled
  process (G-ENG-25).
- First adopter: booking-service. memory.md "Adoption backlog" ranks the gaps that matter for it; check it when
  choosing the next feature.
- jMolecules integration is a documented consideration (`docs/architecture.md`), not yet decided or built.
- In-memory transport (007), see memory.md G-007-01 to G-007-08: Quarkus extension and the constitution amendment
  pending (G-007-01); starter detection deferred (G-007-02); a redelivered start event does not recover a stranded
  start (G-007-03); delivery order is per aggregate, not per saga instance (G-007-04); relay logic may need a shared
  module for Kafka (G-007-06); interop leasing reads all incomplete publications (G-007-07); one running instance per
  log (FR-021, G-007-09).
- Spring Boot starter (008), see memory.md G-008-01 to G-008-04: Modulith event externalization and `Scenario`
  support deferred (G-008-01); the Actuator endpoint is read-only (G-008-02); events published through Spring's own
  `ApplicationEventPublisher` do not reach sagas (G-008-03); repository tests use `ApplicationContextRunner` /
  `SpringApplicationBuilder`, not `@SpringBootTest` (G-008-04). An event a step of its own instance runs is not handed
  to other saga types (G-ENG-24).
- Postgres storage (003), see memory.md for detail: concurrency is a row lock plus a forward-transition check,
  with no version field (G-003-02, -03, -13, -19 to -22; G-003-01/-02 explicitly deferred); steps completed in one
  `save()` are ordered by a step-name tiebreak (G-003-04, -16); no lifecycle-history query API, FR-012
  (G-003-24); the host applies `schema.sql`, no migration tool (G-003-25); `DataSource`/pool wiring belongs to
  the starter (G-003-26).

- Saga test fixture (009), see memory.md G-009-01 to G-009-07: no Spring helper or test slice (G-009-01); its
  in-memory plug-ins duplicate the runtime's test doubles (G-009-02); the value rule is duplicated from Postgres,
  guarded by a contract test (G-009-03); in record mode an outbox command still needs a registered handler class
  (G-009-04); aggregates without a no-argument constructor are shared, not copied (G-009-05); `Long` read-back as
  `Integer` is not emulated (G-009-06); outbox commands are reported at delivery, after the sender's own (G-009-07).

- Observability (010), see memory.md G-010-01 to G-010-06: the Kafka transport must carry the same trace context
  (G-010-01); Spring Modulith listeners' trace continuity is Modulith's (G-010-03); diagnostics carry trace ids as keys,
  not MDC (G-010-04); saga metrics need an OpenTelemetry metric exporter, Spring Boot defines none (G-010-05); no
  Micrometer instrumentation, and it must never run beside the OpenTelemetry one (G-010-06).

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
