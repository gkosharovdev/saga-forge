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
  compensation, never retried; renamed `COMPENSATION_FAILED` by 011); `SagaInstance.compensationTrigger`, `StepRecord.compensation` (`StepCompensation`),
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
  2026-11-12 (1,612 tests; the opt-in `TelemetryOverheadTest` skipped). With 011 (outcome events) the whole build, container suites included, passed on 2026-11-20 (1,669 tests; `TelemetryOverheadTest` skipped). With 012 (outbox command failure feedback) the whole build, container suites included, passed on 2026-11-24 (1,768 tests; `TelemetryOverheadTest` skipped).
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
- Implemented and tested, including container suites, added by `specs/011-saga-outcome-events`: one unit of work and
  outcome events. Every runtime write (step, start-compensation, compensation, `FAILED` and failed-compensation
  writes) runs through one package-private `runtime.internal.UnitOfWork` (scopes `forInstance`/`forNewInstance`/
  `standalone`; `Work` admit/perform; results `Committed`/`NotRun`/`Failed`/`Conflicted`), held to that by the ArchUnit
  rule in `UnitOfWorkBoundaryTest`. Core gains `core.outcome`: sealed `SagaOutcome` = `SagaCompleted`, `SagaFailed`
  (`FailurePhase` `STEP`/`COMPENSATION`) and `SagaCompensated`, with `OutcomeTrigger`; `eventId` is `sagaId:STATUS`.
  The unit of work that writes a terminal status appends one outcome event last to its batch's `events` and
  `recordedEvents` (no failure message, context value or saga field); status writes release their `AFTER_COMMIT`
  outbox after the instance lock and report its failures in `outboxFailures` (now allowed for `FAILED` too).
  `SagaRuntime.Builder.outcomeEvents(boolean)` (default `true`), starter property `saga-forge.outcome-events.enabled`,
  `SagaFixture.Builder.outcomeEvents`; the runtime accepts a delivered outcome event and hands it to no saga; the
  in-memory transport keys it `saga:<sagaId>`. Fixture: `EventResult.outcomeEvents()`, `Then.expectOutcomeEvent`/
  `expectNoOutcomeEvent`. `REQUIRES_INTERVENTION` is renamed `COMPENSATION_FAILED` (`SagaStatus`, `HandlingOutcome`,
  telemetry `compensation_failed`) and its lifecycle kind removed; existing databases apply `schema-upgrade-011.sql`.
  Property 38. The Spring example's `FailedOrders` listens for `SagaFailed`.
- Implemented and tested, including container suites, added by `specs/012-outbox-command-failure-feedback`: outbox command failure feedback
  (closes G-ADOPT-02). Core gains `core.spi.CommandFailure` (saga-scoped `CommandId`, `FINAL`/`EXHAUSTED`, cause type,
  attempts; the command type comes from the id; no message), `FailureClassification.of(Throwable)` (marker walk shared
  by runtime, transport and fixture), the default SPI methods `CommandReceiver.receiveFailure(CommandFailure,
  Consumer<LocalTransaction>)` and `MessageStore.setAside(LocalTransaction, ...)`, `CompensationTriggerKind.COMMAND_FAILED`
  and a nullable `commandType` on `CompensationTrigger` and `OutcomeTrigger` (old constructors kept). The relay retries a
  failed outbox command with its own backoff (never `@RetryPolicy`), gives up at once on a `FinalFailure` (set aside
  `FINAL_FAILURE`) or at `maxAttempts` (`MAX_ATTEMPTS`), and reports a saga-scoped one; the set-aside runs inside the
  runtime's reaction transaction, and a throwing report is retried. The runtime's `CommandFailureHandler` (shared
  `InstanceLocks`, one `UnitOfWork`) resolves the `Sender`: a step's command → `StartCompensation` with the trigger,
  then `CompensationRun` (the sender compensated too); a compensation's command → `RecordCompensationCommandFailure`
  (`COMPENSATED → FAILED`, instance `COMPENSATION_FAILED`, which Postgres `ForwardTransition` allows only together);
  anything else → `saga.command.failure.ignored`. Postgres `PostgresMessageStore` and the starter's
  `ModulithMessageStore` implement the transactional set-aside; the trigger JSON gains `commandType` (no upgrade
  script). Telemetry: delivery span `saga_forge.command.classification`/`reported`, counter
  `saga_forge.command.failures`, a `saga_forge.handle` span per report. Fixture: `SagaFixture.Builder.outboxCommandAttempts`
  (default 1); `saga-forge-test` has the in-memory transport as a test dependency for `OutboxCommandFailureParityTest`.
  Property 39.
- Implemented and tested, including container suites, added by `specs/013-active-standby-relay` (closes G-007-09 and
  G-017-02): several application instances share one message store with one active relay. Core gains
  `core.spi.store.LeaderElection` (`tryAcquire`/`renew`/`release`/`current`), `LeaderLease` (holder, host, fencing
  token, expiry), `LeadershipLostException`, `MessageStore.leaderElection()` and the fenced
  `MessageStore.lease(LeaderLease, now, leaseDuration, limit)`; `ConsumerHeartbeat` and the heartbeat methods are
  removed. The transport's `LeaderElector` (client-go shaped: `LeadershipSettings` lease 30 s, renew 10 s, check 5 s;
  local distrust at `lastCallStart + lease - renewInterval`) drives the relay, which leases only while it leads, steps
  down when fenced, finishes and keeps renewing its in-flight deliveries, and runs the retention sweep only as leader;
  `InMemoryTransport.leadership()` → `LeadershipStatus` (`RelayRole` `ACTIVE`/`STANDBY`). Postgres
  `PostgresLeaderElection` over `saga_message_store_leader` (one row, fencing token incremented per acquisition); the
  lease transaction holds the row `FOR SHARE`, so a takeover waits for it; existing databases apply
  `schema-upgrade-013.sql` (drops `saga_message_store_consumer`). `ModulithMessageStore` delegates the leadership to
  its records store. Starter: `saga-forge.transport.leadership.*` (replacing `heartbeat-interval`), the `leadership`
  object in the `sagaforge` endpoint, health `DEGRADED` while leaderless. Telemetry gauge `saga_forge.relay.active`,
  counter `saga_forge.relay.leadership_changes`, log events `saga.transport.leadership.*`.
- Whole build, container suites included (`mvn -Pcontainer-tests verify`), passed on 2026-11-25 (1,881 tests, 0 failures; the release build `-P release` with Javadoc also passes).
- With 015 (saga operations) the whole build, container suites included, passed on 2026-11-26 (2,080 tests, 0 failures; 6 skipped: `TelemetryOverheadTest` and the five scheduling cases of `TransportAcceptanceSuite` under Modulith interop, G-017-01).
- Implemented and tested, added by `specs/017-saga-deadlines` (closes G-ENG-25; taken ahead of 013): saga deadlines and
  scheduled commands. Core gains `@StartDeadline(name, duration | durationFrom, cancelledBy)` (repeatable) and
  `@DeadlineExpired(name)` on a `@SagaStep` with no `associationProperty`, taking nothing or `core.deadline.DeadlineReached`
  (scanner rules R24 to R29, Properties 40 to 43); `OpenDeadline`, `SagaInstance.openDeadlines`,
  `CompensationTriggerKind.DEADLINE_EXPIRED` with `deadlineName` on `CompensationTrigger`/`OutcomeTrigger`,
  `DeadlineDurationException`; `CommandGateway.schedule(command, Instant)` → `ScheduledCommandId` and `cancel(id)`;
  `Outbox.supportsScheduling`/`cancel`, `OutgoingMessages.scheduled`, `NewMessage.notBefore`, `MessageStore.cancel` and
  `MessageStatus.CANCELLED`. The runtime stores a deadline as a message with a due time in the starting unit of work,
  closes it on a `cancelledBy` step, on a restart of the same name and whenever the instance leaves `RUNNING`
  (`EngineSagaLifecycle.replace`), and ignores a late delivery (`IGNORED`, `saga.deadline.ignored`); a startup check
  rejects deadlines over an outbox that cannot schedule; `${key:default}` durations resolve through
  `SagaRuntime.Builder.propertyResolver`. The in-memory transport and `PostgresMessageStore` schedule and cancel
  (existing databases apply `schema-upgrade-017.sql`); the starter shares one `Clock`, resolves placeholders from the
  `Environment` and refuses deadlines under Modulith interop (`DeadlinesRequireOwnLogFailureAnalyzer`). Fixture:
  `whenTimeElapses`/`whenTimeReaches`/`givenTimeElapses`, `expectOpenDeadline`/`expectNoOpenDeadline`/
  `expectDeadlineExpired`/`expectDeadlineIgnored`, `InstanceView.openDeadlines`, `Builder.property`. Telemetry counter
  `saga_forge.deadlines`. Examples: `CheckoutSaga`'s payment window and `FulfilmentSaga`'s courier assignment. Durable
  retry backoff (G-005-01/-02) is not part of it.
- Implemented and tested, including container suites, added by `specs/015-saga-operations` (closes G-006-03, G-006-05,
  G-008-02, G-ENG-05, G-013-05 and G-003-24; without retry, G-015-01 / roadmap 015b): saga operations. Core gains
  `core.operations`: `SagaOperations` (`list(SagaInstanceQuery)` with a keyset `PageCursor` → `SagaInstancePage`,
  `find`, `findByAssociation`, `history`, `context`, `count` → `InstanceCounts`; repairs `resumeCompensation`,
  `compensate`, `markResolved`, each taking an `OperatorRequest` and returning `RepairResult.Applied`/`NotApplied`
  (`NOT_FOUND`, `WRONG_STATUS`, `ALREADY_RESOLVED`); `recover()` → `RecoveryReport`), `SagaResolution`;
  `SagaInstance.resolution`/`compensationRound`, `CompensationTriggerKind.OPERATOR_REQUEST` with
  `CompensationTrigger.operatorRequest`, lifecycle kinds `COMPENSATION_RESUMED`, `RESOLVED`, `START_RECOVERED` (entries
  carry the operator), and the `StorageProvider` queries (`list`, `count`, `history`, stalled and abandoned instances).
  The runtime's `SagaRuntime.operations()` runs each repair as a `UnitOfWork` work admitted under the instance lock
  after a re-read (`ResumeCompensation`, `CompensateByOperator`, `MarkResolved`, `FailAbandonedStart`); a resume opens a
  new compensation round and outcome ids gain `:r<round>` (Property 38 revised). `RecoverySweeper` (`RecoverySettings`,
  off by default for a hand-assembled runtime, `AbandonedStartPolicy` `REPORT` default or `FAIL`) runs on every
  instance; the router adopts a stranded `CREATED` start on a redelivered start event; `SagaRuntime.stop()`. Telemetry:
  span `saga_forge.operation`, counters `saga_forge.operations`/`saga_forge.recovery`, gauge `saga_forge.instances`.
  Postgres implements the queries and persists the new fields (existing databases apply `schema-upgrade-015.sql`). The
  starter: `saga-forge.recovery.*` (sweeps on by default), `saga-forge.operations.expose-context-values`,
  `saga-forge.telemetry.instance-count-interval`, the `sagas` Actuator endpoint (write access off unless
  `management.endpoint.sagas.access=unrestricted`; needs a principal), health details for unresolved counts. Fixture:
  `SagaFixture.operations()`, `whenOperator(...)`, `Builder.recovery`, `Then.expectResolved`/`expectNotResolved`/
  `expectHistory`, `InstanceView.history`/`resolution`. Properties 44 to 46.
- Implemented and tested, including container suites, added by `specs/014-publish-once` (closes G-008-03 and G-ENG-24;
  CI run 45909269287 on 2026-11-26 green: unit tests on JDK 21 and 25, integration job with Postgres 192, starter 55
  (5 Modulith scheduling skips, G-017-01) and Spring example 7 container tests; locally `mvn verify` 1,852 tests and the
  release build with Javadoc): publish once, with Saga Forge's own log beside Spring Modulith's registry
  (two logs; interop unchanged). Core's `EventReceiver` gains the default `receive(event, sourceSagaId)`; the runtime's
  `UnitOfWork` hands a recorded event its own instance ran in process to the outbox when another saga type has a step
  for it (`runtime.internal.EventSubscriptions`), and `EventRouter` gives the source instance no result when its step
  for the event is `COMPLETED` (Property 47); `SagaRuntime.eventTypes()`. The relay and the fixture's
  `LoopBackTransport` deliver with the stored `sourceSagaId`. Starter: `InboundSagaEventListener` stores events
  published through Spring's `ApplicationEventPublisher` (also Spring Data `@DomainEvents`) whose type a saga has a step
  for, in the publishing transaction, or records them in the unit of work on the thread (package-private
  `AlreadyPublishedEvents`, `PublishingGuard`); `saga-forge.application-events.inbound.enabled` (default `true`);
  `ApplicationEventPublishingEventPublisher` makes the starter's `EventPublisher` publish to Spring too;
  `application-events.mode=after-delivery` is deprecated and refused while the route is on; with deadlines in the model
  and `saga-forge.transport.modulith-interop` not set, the transport keeps the own log. The Spring example's
  `OrderPlacement` publishes through `ApplicationEventPublisher`.
- Implemented and tested, added by `specs/016-marker-spi-jmolecules` (closes G-ADOPT-04 and G-002-04): vocabularies of
  domain markers and event harvesting. Core gains `core.spi.model`: `DomainTypeResolver` (one vocabulary: `name`,
  `role`/`roles`, `identifiers`, `commandHandler`, `describe`), `DomainTypes` (enabled vocabularies composed, Saga
  Forge's annotations first and always on; `builtIn()`, `of(...)`), `DomainRole`, `RoleMarker`, `IdentifierMember`; the
  scanner, runtime, transport ordering keys, `DomainEvents.record` and the starter read aggregates, identifiers, events,
  commands and handlers only through it, and every error text names the marker used (built-in texts unchanged). Rules
  R30 (one role per type, identifiers agree across vocabularies) and R31 (a by-type link resolves to exactly one
  aggregate); Properties 16/17 restated, 48 (vocabulary equivalence) and 49 (harvesting). SPI
  `core.spi.AggregateEventSource` (`supports`, `takeEvents`): the runtime's package-private `EventHarvest` records an
  aggregate's held events after each aggregate handler and before the save (`SagaRuntime.Builder.aggregateEventSource`,
  repeatable, first supporting source wins). New module `saga-forge-jmolecules` (`JMoleculesDomainTypeResolver`:
  `AggregateRoot`/`@AggregateRoot` with `@Identity` or `getId()`, `DomainEvent`/`@DomainEvent`, cqrs `@Command` and
  `@CommandHandler`, links by `Association`/`@Association`/`Identifier` members; no event source). Builders gain
  `domainTypes(...)` (runtime, `InMemoryTransport`, `SagaFixture`) and `SagaFixture.Builder.aggregateEventSource`. The
  starter composes one `DomainTypes` bean from `DomainTypeResolver` beans (jMolecules added unless
  `saga-forge.jmolecules.enabled=false`), passes `AggregateEventSource` beans in order, and the `sagaforge` endpoint
  lists `vocabularies` and `eventSources`; no Spring Data event source (014's inbound route covers `@DomainEvents`).
  Example `saga-forge-example-jmolecules`: the canonical saga over a jMolecules-only domain with an `OrderEventSource`.
  Guide page `docs/guide/vocabularies.md`.
- With 016 (vocabularies and harvesting) the whole build, container suites included (`mvn -Pcontainer-tests verify`), passed on
  2026-11-27 (2,233 tests, 0 failures; 6 skipped, as with 015); the release build `-P release` with Javadoc also passes.
- Implemented and tested, including container suites, added by `specs/018-versioned-schema-migrations` (closes G-003-25):
  versioned schema migrations. `saga-forge-storage-postgres` ships Flyway SQL migrations `V1__base_schema` (the 0.1.0
  schema), `V2__saga_outcome_events` (011), `V3__saga_deadlines` (017), `V4__active_standby_relay` (013) and
  `V5__saga_operations` (015), in the order their changes reached `schema.sql`, at
  `classpath:io/github/gkosharovdev/sagaforge/storage/postgres/migration` (not under `db/migration`, which Flyway
  scans recursively), applied under Saga Forge's own history table `saga_forge_schema_history`. Released versions
  (V1 to V4; V5 at its release) are frozen by `released-migrations.sha256`. The module has no Flyway dependency:
  `PostgresSchema` (location, table, `versions()`), `PostgresSchemaValidator` (`validate`, `detectVersion` →
  `SchemaDetection` `Empty`/`Matched`/`Unmatched`, `appliedVersion`) compares `pg_catalog` with per-version snapshots
  `schema-levels/V<N>.json` by meaning (failures for tables, columns, keys, foreign keys, checks; indexes only warn;
  extras ignored) → `SchemaValidationReport`/`SchemaDifference`; `SagaSchemaException`. Starter:
  `saga-forge.storage.postgres.initialize-schema` gains `validate` and `migrate` (`never` also logs the check),
  `saga-forge.storage.postgres.migration.history-table`/`baseline-version`; `SchemaInitializer` runs after the
  application's database initialisers (`@DependsOnDatabaseInitialization`) and drives its own Flyway
  (`FlywaySchemaMigrator`, Flyway optional; always `baselineOnMigrate`, at the detected version or 0, G-018-07);
  `SagaSchemaFailureAnalyzer`; the `sagaforge` endpoint's `schema` object. Guide page `docs/guide/schema.md`.
- With 018 (versioned schema migrations) the whole build, container suites included (`mvn -Pcontainer-tests verify`), passed
  on 2026-11-27 (2,317 tests, 0 failures; 6 skipped, as with 015); the release build `-P release` with Javadoc also passes.
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
  `DomainEvents`/`EventPublisher`, `core.operations` (`SagaOperations`, `SagaInstanceQuery`/`PageCursor`/
  `SagaInstancePage`, `OperatorRequest`, `RepairResult`, `SagaResolution`, `InstanceCounts`, `RecoveryReport`), SPI
  (`core.spi.model` `DomainTypes`/`DomainTypeResolver`, `AggregateEventSource`, 016). Zero dependencies.
- `saga-forge-runtime` — the saga runtime; depends on the JDK, `saga-forge-core` and `opentelemetry-api` (010) only. Public surface:
  `SagaRuntime` with its host-facing builder (`operations()`, `stop()`, `Builder.recovery`), `StartupReport`,
  `SagaRuntimeStartupException`, `ImmediateDispatchOutbox`, `RecoverySettings`, `AbandonedStartPolicy`; everything else is package-private in `runtime.internal`, where every write goes through
  `UnitOfWork` (ArchUnit test scope only, `UnitOfWorkBoundaryTest`). Every contract it needs lives
  in `core.spi` (`EventReceiver`, `TransactionBoundary`, `Outbox`, `HandlerParameterResolver`,
  `SagaObjectProvider`, ...). Its test-jar ships the in-memory doubles (`InMemoryTransactionBoundary`,
  `TransactionalInMemoryStorageProvider`, `TestTransport`, ...).
- `saga-forge-spring-boot-starter` — auto-configuration (008), see its `README.md`: `SagaRuntimeConfiguration`
  (`SagaModelCollector`, the runtime, `CommandGateway`/`EventPublisher` beans, `SagaRuntimeLifecycle`),
  `SpringTransactionConfiguration`, `PostgresStorageConfiguration` (`SchemaInitializer`), `SpringHostConfiguration`,
  `ApplicationEventsConfiguration`, `SagaForgeActuatorConfiguration`, `SagasEndpointConfiguration` (`SagasEndpoint`, 015), the failure analyzers, `SagaForgeProperties`;
  plus 007's transport wiring (`InMemoryTransportConfiguration`, `TransportLifecycle`, `EventReceiverCustomizer`,
  `ApplicationEventBridge`, `ModulithInteropConfiguration`/`ModulithMessageStore`), and 018's `SchemaInitializer`/
  `FlywaySchemaMigrator` (Flyway optional) with `SagaSchemaFailureAnalyzer`. No `SagaRuntimeConfig` for users
  to hand-assemble. Spring code stays in this module and `saga-forge-example-spring`.
- `saga-forge-storage-postgres` — `StorageProvider` + `SagaContextStore` over plain JDBC, with Jackson 3
  (`tools.jackson.core:jackson-databind`) encoding the JSONB columns; also persists lifecycle history
  (`saga_lifecycle_history`). Jackson and the driver stay in this module, never in core. Ships the schema as versioned
  Flyway migrations (`migration/V<N>__*.sql`) plus `schema.sql` and the upgrade scripts, and `PostgresSchemaValidator`
  (018); Flyway is a test dependency only.
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
- `saga-forge-jmolecules` — the jMolecules vocabulary (016): `JMoleculesDomainTypeResolver`; depends on
  `saga-forge-core` and `jmolecules-ddd` (events and cqrs artifacts optional). See its `README.md`.
- `saga-forge-example` — `OrderProcessingSaga` is the canonical reference pattern; keep it in sync with
  whatever the annotation model currently supports. Package `example.checkout` holds the README's domain story,
  `CheckoutSaga` and `FulfilmentSaga` (Inventory, Payments and Logistics as stand-in services), told in full in
  `docs/use-cases.md` §1, including the payment-window and courier-assignment deadlines (017) (G-DOC-04: two stories for now).
- `saga-forge-example-spring` — the canonical saga as a Spring Boot application (Spring Data JDBC `Order`, service
  beans, no assembly code). Keep it in step with `saga-forge-example`; it keeps amounts as `String` in `SagaContext`
  (G-003-07).
- `saga-forge-example-jmolecules` — the canonical saga as a Spring Boot application over a domain marked only with
  jMolecules (typed `OrderId`, `OrderEventSource`); keep it in step with the other two examples.

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
- Domain roles (aggregate, identifier, domain event, command, command handler) come from `DomainTypes`, never from a
  direct annotation check: Saga Forge's annotations are the built-in vocabulary, jMolecules' an optional second one.
  The saga side (`@Saga`, `@SagaStep`, ...) is Saga Forge's only. Ask `DomainTypes` in new code that needs a role.
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
- Compensation limitations, see memory.md G-006-02 to G-006-09: no confirmation wait for an undo (G-006-02); an
  operator resumes `COMPENSATION_FAILED` since 015 (G-006-03 closed); no "irreversible step" marker (G-006-04); a stalled
  `COMPENSATING` instance resumes through a recovery sweep since 015 (G-006-05 closed); a compensation sees the trigger's event type, not the
  event (G-006-06); upgraded instances may get a name-ordered `completionSequence` (G-006-07); compensations block the
  caller and hold the instance (G-006-08); a failed compensation is not retried on its own, only by an operator's resume (G-006-09). A compensation
  interrupted before its commit runs again on resume, so its commands must be idempotent (pass `CommandId` on).
- `SagaContext` values are a separate `SagaContextRecord` behind `SagaContextStore`; the runtime leaves
  `SagaInstance.state` empty and treats other saga fields as dependencies (G-ENG-06, use-or-drop still open).
- Engine limitations, see memory.md G-ENG-05 to G-ENG-13: a crash between the creation write and the first unit of
  work strands a `CREATED` instance until its start event is redelivered and adopts it (G-ENG-05 closed by 015); the default `ImmediateDispatchOutbox` is at most once, an outbox needs
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
- Waiting timeouts are saga deadlines (017): `@StartDeadline` on a step, one `@DeadlineExpired` timeout step per
  deadline. Don't add a second scheduling mechanism; durable retry backoff (G-005-01/-02) should reuse the message
  store's due times.
- First adopter: booking-service. memory.md "Adoption backlog" ranks the gaps that matter for it; check it when
  choosing the next feature.
- jMolecules integration is built (016): see `docs/architecture.md` "Vocabularies of domain markers".
- In-memory transport (007), see memory.md G-007-01 to G-007-08: Quarkus extension and the constitution amendment
  pending (G-007-01); starter detection deferred (G-007-02); a redelivered start event does not recover a stranded
  start (G-007-03); delivery order is per aggregate, not per saga instance (G-007-04); relay logic may need a shared
  module for Kafka (G-007-06); interop leasing reads all incomplete publications (G-007-07); several instances share a
  log with one active relay since 013 (G-007-09 closed).
- Spring Boot starter (008), see memory.md G-008-01 to G-008-04: Modulith event externalization and `Scenario`
  support deferred (G-008-01); the `sagaforge` endpoint is read-only, repairs go through 015's `sagas` endpoint (G-008-02 closed); events published through Spring's own
  `ApplicationEventPublisher` reach sagas since 014 (G-008-03 closed); repository tests use `ApplicationContextRunner` /
  `SpringApplicationBuilder`, not `@SpringBootTest` (G-008-04). An event a step of its own instance runs reaches other
  saga types since 014 (G-ENG-24 closed, Property 47).
- Publish once (014), see memory.md G-014-01 to G-014-06: no order between Saga Forge's log and Modulith's registry
  (G-014-01); whether a Kafka transport publishes integration events is undecided (G-014-02); switching
  `modulith-interop` strands in-flight messages (G-014-03); with `ImmediateDispatchOutbox` or a decorator that drops the
  source, the source instance reports `DUPLICATE` on a fanned-out event (G-014-04); an event published in a host
  transaction waits for the relay's next poll (G-014-05); three planned tests were not built (G-014-06); the inbound listener resolves the runtime lazily (G-014-07). An
  `EventReceiver` decorator forwards `receive(event, sourceSagaId)`.
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

- Outcome events (011), see memory.md G-011-01 and G-011-06 to G-011-08: no saga step can react to another saga's
  outcome event, so follow-up work starts from a listener (G-011-01); a status write's batch reuses the rolled-back
  unit of work's id (G-011-06); the fixture collects outcome events instead of delivering them (G-011-07).

- Outbox command failure feedback (012), see memory.md G-012-01 to G-012-05: a compensation's failed command cannot
  correct a `COMPENSATED` instance (G-012-01); a `COMPLETED` saga is not told (G-012-02); no step can take the failure,
  the reaction is always full compensation (G-012-03); a crash after the reaction's first commit resumes only on the
  next event (G-012-04); the fixture redelivers without backoff (G-012-05).

- Saga deadlines (017), see memory.md G-017-01 to G-017-10: none with Spring Modulith interop (G-017-01; since 014 the
  starter keeps the own log for a deadline model unless the property is set); overdue messages run in stored order (G-017-03); latency up to the
  poll interval (G-017-04); a deadline leased before its cancel is delivered and ignored (G-017-05); a scheduled command
  is not cancelled with its saga (G-017-06); a deadline does not resume a `COMPENSATING` instance (G-017-07);
  implementation decisions (G-017-08); the fixture duplicates the scheduling (G-017-09); no Spring example or Spring
  container test for deadlines (G-017-10).

- Active/standby relay (013), see memory.md G-013-01 to G-013-05: a message appended on a standby waits for the
  leader's poll (G-013-01); delivery throughput is one relay's, no partitioned leasing (G-013-02); takeover timing, not
  safety, depends on instance clocks (G-013-03); the redelivery-after-commit failover case is not forced in a test
  (G-013-04); a failover between creation write and first unit of work is recovered by the start event's redelivery since 015 (G-013-05 closed).
  Never deliver outside `MessageStore.lease(LeaderLease, ...)`: the fencing token is the multi-instance safety.

- Saga operations (015), see memory.md G-015-01 to G-015-09: no retry of a failed step, roadmap 015b next (G-015-01);
  sweeps run on every instance, safe by the instance lock, never by leadership (G-015-02); a resume keeps only the
  latest round's compensation failure (G-015-03); repairs run compensations in the caller's thread (G-015-04); no bulk
  repair (G-015-05); listings are not snapshot-consistent (G-015-06); the abandoned-start report is deduplicated per
  process (G-015-07); a stopped runtime and transport start again since G-015-08 closed; uncovered contract cases (G-015-09). Every repair
  is a `UnitOfWork` work whose admission re-reads the instance under its lock; keep it that way.

- Vocabularies and harvesting (016), see memory.md G-016-01 to G-016-09: an outbox command still needs Saga Forge's
  `@Command(delivery = OUTBOX)` (G-016-01); the fixture sees no Spring-published events (G-016-02); jMolecules has no
  event-holding convention, so no event source ships (G-016-03); `DomainEvents.capture(Runnable)` knows only the
  built-in markers (G-016-04); plain-typed identifiers are never links (G-016-05); a by-type event is keyed by its
  identifier type (G-016-06); no Modulith-interop or `@TransactionalEventListener` harvest test (G-016-07); no
  invalid-variant equivalence property (G-016-08); package scanning loads each candidate class (G-016-09).

- Versioned schema migrations (018), see memory.md G-018-01 to G-018-08: Flyway only, no Liquibase (G-018-01);
  detection cannot see data rewrites (G-018-02); snapshots taken on Postgres 16 (G-018-03); rolling-deploy compatibility
  of a version is a review rule (G-018-04); no MySQL/MongoDB migrations (G-018-05); the schema status only on the
  `sagaforge` endpoint (G-018-06); Saga Forge's history starts with a baseline row at 0 beside application tables
  (G-018-07); Boot's `OutputCaptureExtension` does not run here (G-018-08).

- Observability (010), see memory.md G-010-01 to G-010-06: the Kafka transport must carry the same trace context
  (G-010-01); Spring Modulith listeners' trace continuity is Modulith's (G-010-03); diagnostics carry trace ids as keys,
  not MDC (G-010-04); saga metrics need an OpenTelemetry metric exporter, Spring Boot defines none (G-010-05); no
  Micrometer instrumentation, and it must never run beside the OpenTelemetry one (G-010-06).

## Agent Working Rules For This Repo
- Sources are formatted with google-java-format through Spotless: run `mvn spotless:apply` before committing; `mvn verify` and
  the CI `format` job fail on unformatted code. The code compiles with `maven.compiler.release` 21; CI also tests on JDK 25.
- A schema change adds `migration/V<N+1>__*.sql`, updates `schema.sql` and an upgrade script, and regenerates
  `schema-levels/V<N+1>.json` (`SchemaSnapshotTest -Dsaga-forge.write-snapshots=true`); never edit a released version.
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
- Favour composition over inheritance: reuse behaviour by holding and delegating to collaborators, not by
  extending a concrete class. Inheritance is for types designed for it (sealed hierarchies, value variants); a
  new class hierarchy needs a justification in `plan.md` (constitution, Development Workflow).
- Test an abstraction before you propose it: a newly invented interface, SPI, abstract type or extension point
  gets a concrete caller and a test against it (a test double or a second implementation) first. Don't put an
  abstraction in `plan.md`, `tasks.md` or code that no test has exercised.
- Favour intention-revealing abstractions and interfaces that the community already knows. When you map a
  request to class design and an invocation chain, rate each proposed abstraction in the narrative by how
  popular, and so how understandable, it is: established patterns, well-known libraries and frameworks, common
  engineering vocabulary (Repository, Gateway, Outbox, Policy, Handler, Resolver). If an abstraction or its name
  is too boutique, with no counterpart in known community code or common usage, avoid it and propose a
  recognisable alternative.
- Before class-level design (`/speckit-plan`'s data model and contracts) and before implementation
  (`/speckit-implement`), propose loading the `effective-java`, `clean-code-java` and `solid-design-review`
  skills, and apply them to the types you design.

## Consumer Documentation
- `docs/guide/` is the user guide for applications that use Saga Forge (no spec numbers or gap ids in the prose,
  except `limitations.md`). Every Java snippet there is copied from compiling code and names its source file.
- When an annotation, a scanner or startup error text, a property or a recipe's source changes, update the matching
  guide page, `docs/agents/saga-forge/SKILL.md` and `docs/agents/AGENTS-snippet.md`, then run
  `docs/guide/build-llms-full.sh` (CI fails when `llms-full.txt` is out of date). Gaps: memory.md G-DOC-01, G-DOC-02.
- The site is built from `docs/guide/` by MkDocs Material (`mkdocs.yml`, `.github/workflows/docs.yml`, strict mode, deployed
  to GitHub Pages from master); `docs/site-hooks/repo_links.py` points links that leave `docs/guide/` at GitHub.

## Fast Discovery Checklist
- Read `README.md` for the pitch and the canonical example.
- Read `docs/architecture.md` for module responsibilities, autoconfiguration detection matrix, and vocabulary.
- Read `docs/use-cases.md` for worked multi-step examples including failure/retry/compensation.
- Cross-check any annotation claim against the actual source in `saga-forge-core` before trusting a doc.
