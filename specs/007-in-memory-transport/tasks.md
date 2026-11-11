---
description: "Task list for In-Memory Transport with a Durable Message Log"
---

# Tasks: In-Memory Transport with a Durable Message Log

**Input**: Design documents from `/specs/007-in-memory-transport/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md),
[data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md)

**Key decisions (plan.md Summary, research.md)**:
- **Rename first** (§14): `EventHandoff` → `Outbox` (`handOff` → `store`), `HandedOffEvents` → `OutgoingMessages`,
  `DispatchingEventHandoff` → `ImmediateDispatchOutbox`, `SagaEngine.Builder.handoff` → `outbox`,
  `SagaHandlingResult.handoffFailures` → `outboxFailures`. Pure rename, no behaviour change.
- **Outbox** (§1, §2): the transport is an `IN_TRANSACTION` `Outbox` that appends to a `MessageStore` in the unit of work's
  transaction; the new `Outbox.committed(batch)` wakes the relay; a 500 ms poll is the fallback.
- **Outbox commands** (§3, §4): `@Command(delivery = CommandDelivery.OUTBOX)`; buffered in the unit of work, carried in
  `OutgoingMessages.commands`, handled through the new `CommandReceiver`, marked consumed inside the handler's transaction.
  Scanner rule R23, Property 35.
- **Relay** (§5, §6): leased leases, one in-flight entry per ordering key (aggregate), 4 workers, backoff 1 s → 1 min,
  `SET_ASIDE` after 10 attempts or an unreadable payload; events marked consumed after `receive` returns.
- **Postgres** (§8): `PostgresMessageStore`, tables `saga_message_store` and `saga_message_store_consumer`.
- **Spring** (§10): `SpringTransactionBoundary`, lifecycle, executor, event bridge; Spring Modulith interop through
  `EventPublicationRegistry` with Saga Forge's delivery records beside it. Risk R1 spiked first.

**Tests**: required (spec Independent Tests, SC-001 to SC-008, quickstart.md scenarios 1 to 13). **Not TDD**, the same
convention as 004 to 006:
- each task writes production code first, and its tests come in the same task or a later task of the same phase;
- no task in the middle of a phase runs tests, though compiling is allowed;
- each phase ends with exactly one checkpoint task that runs the unit suites of the modules it touched;
- Testcontainers classes (`@Tag("container")`) are edited and compiled only, never run by the implementer. They are
  handed to the user at T082.

**Organization**: by user story (spec.md: US1 P1, US2 P1, US3 P2, US4 P3). Postgres and Spring wiring are their own
phases without a story label, as Postgres was in 006.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: US1 to US4; Setup, Foundational, Postgres, Spring and Polish tasks carry no story label
- All paths are relative to the repository root `saga-forge/`

## Path Conventions

- **Core**: main `saga-forge-core/src/main/java/io/github/gkosharovdev/sagaforge/core/` → `core/…`; tests
  `saga-forge-core/src/test/java/io/github/gkosharovdev/sagaforge/core/` → `core-test/…`
- **Engine**: main `saga-forge-engine/src/main/java/io/github/gkosharovdev/sagaforge/engine/` → `engine/…` (runtime in
  `engine/internal/`); tests `saga-forge-engine/src/test/java/io/github/gkosharovdev/sagaforge/engine/` → `engine-test/…`
  (`support/`, `fixtures/`, `internal/`)
- **Transport**: main `saga-forge-transport-inmemory/src/main/java/io/github/gkosharovdev/sagaforge/transport/inmemory/`
  → `transport/…` (package-private runtime in `transport/internal/`); tests
  `saga-forge-transport-inmemory/src/test/java/io/github/gkosharovdev/sagaforge/transport/inmemory/` → `transport-test/…`
- **Postgres**: main `saga-forge-storage-postgres/src/main/java/io/github/gkosharovdev/sagaforge/storage/postgres/` →
  `postgres/…`; resources `saga-forge-storage-postgres/src/main/resources/io/github/gkosharovdev/sagaforge/storage/postgres/`
  → `postgres-res/…`; tests `saga-forge-storage-postgres/src/test/java/io/github/gkosharovdev/sagaforge/storage/postgres/`
  → `postgres-test/…`
- **Starter**: main `saga-forge-spring-boot-starter/src/main/java/io/github/gkosharovdev/sagaforge/spring/` → `spring/…`;
  resources `saga-forge-spring-boot-starter/src/main/resources/` → `spring-res/…`; tests
  `saga-forge-spring-boot-starter/src/test/java/io/github/gkosharovdev/sagaforge/spring/` → `spring-test/…`
- **Example**: `saga-forge-example/src/{main,test}/java/io/github/gkosharovdev/sagaforge/example/` → `example/…`,
  `example-test/…`
- **Compile check** (allowed in any task, runs no test): `mvn -B -pl <modules> -am -DskipTests clean verify`. The
  `clean` avoids stale IDE classes (G-REPO-02).
- **Unit checkpoint**: `mvn -B -pl <modules> -am clean verify`. Container suites are excluded without
  `-Pcontainer-tests`.

---

## Phase 1: Setup

**Purpose**: formal design first (Constitution Principle III) and module scaffolding.

- [X] T001 Update `.kiro/specs/saga-forge-framework/design.md`:
  - **Annotations**: extend the `@Command` section with the `delivery` attribute and the `CommandDelivery` enum
    (`IN_UNIT_OF_WORK` default, `OUTBOX`), per [contracts/core-spi.md](./contracts/core-spi.md).
  - **Rules table**: add R23 ("every `@Command(delivery = OUTBOX)` type has exactly one `@CommandHandler` in the
    validated model"; message names the command type). State that R23 runs in `validateAll` only, next to Properties
    14 to 20; add `validateOutboxCommands` to the scanner pseudo-code.
  - **Property 35**: a `OUTBOX` command sent inside a unit of work is not handled before that unit of work commits, is
    never handled if it rolls back, and its handler receives the `CommandId` assigned at send time (test
    `OutboxCommandPropertyTest`).
  - **Vocabulary**: replace `EventHandoff`/`HandedOffEvents`/`DispatchingEventHandoff` with `Outbox`/`OutgoingMessages`/
    `ImmediateDispatchOutbox` wherever the document names them (research §14).
  - **Error-message table**: add the R23 message.
  - **Note paragraph**: 007 adds `CommandDelivery`, R23 and Property 35 and leaves Properties 1 to 34 unchanged.
- [X] T002 [P] Add the section "In-memory transport (007), implementation notes" to `.specify/memory/memory.md` after
  G-007-06, holding a dated note pointing to this tasks.md; T081 fills it in.
- [X] T003 [P] Update `saga-forge-transport-inmemory/pom.xml`: keep `saga-forge-core` as the only compile dependency;
  add test dependencies `saga-forge-engine` and its `test-jar` (classifier `tests`), JUnit, AssertJ, jqwik; configure
  `maven-jar-plugin` to build a `test-jar` (it ships `InMemoryMessageStore` and the abstract acceptance suite).
- [X] T004 [P] Update `saga-forge-spring-boot-starter/pom.xml`: add `spring-jdbc` and `spring-tx` (versions from the
  Boot 4.1.2 BOM imported in `dependencyManagement`), optional `saga-forge-transport-inmemory`,
  `saga-forge-storage-postgres`, and `org.springframework.modulith:spring-modulith-events-core` 2.1.1; test
  dependencies `spring-boot-test`, `spring-modulith-starter-jdbc` 2.1.1, Testcontainers Postgres, the transport
  `test-jar`. Fence container tests with the existing `container-tests` profile pattern used in
  `saga-forge-storage-postgres/pom.xml`.

---

## Phase 2: Foundational (blocking prerequisites)

**Purpose**: the outbox rename, the core contracts, the engine hook and the in-memory log double, with every module
compiling and every existing test green.

### Rename (research §14), no behaviour change

- [X] T005 Rename in core: `core/spi/EventHandoff.java` → `core/spi/Outbox.java` (`handOff` → `store`, nested `Timing`
  kept), `core/spi/HandedOffEvents.java` → `core/spi/OutgoingMessages.java`;
  `SagaHandlingResult.handoffFailures()` → `outboxFailures()` in `core/spi/SagaHandlingResult.java`; update every
  Javadoc reference to the old names in `core/spi/EventReceiver.java`, `EventDispatcher.java`, `DomainEventRecorder.java`,
  `TransactionBoundary.java`, `core/command/CommandGateway.java`, `core/event/EventPublisher.java`, using "outbox" in
  prose. Rename `core-test/spi/HandedOffEventsTest.java` → `OutgoingMessagesTest.java` and update
  `core-test/spi/HandlingReportTest.java`.
- [X] T006 Rename in the engine: `engine/DispatchingEventHandoff.java` → `engine/ImmediateDispatchOutbox.java` (Javadoc:
  "dispatches right after commit; at most once, G-ENG-07"); `SagaEngine.Builder.handoff(...)` → `outbox(...)`; every
  identifier containing "handoff"/"handOff" in `engine/SagaEngine.java`, `engine/package-info.java` and
  `engine/internal/{EventRouter,EngineEventPublisher,NonSagaUnitOfWork,SagaUnitOfWork,ReentrancyGuard,EngineSettings,
  CompensationRun,AttemptLoop,Diagnostics,CompensationUnitOfWork}.java` (for example `handoffTiming` → `outboxTiming`,
  `handOffAfterCommit` → `storeAfterCommit`); log message texts say "outbox".
- [X] T007 Rename in engine tests: `support/CapturingEventHandoff.java` → `support/CapturingOutbox.java`;
  `DispatchingEventHandoffTest` → `ImmediateDispatchOutboxTest`, `HandoffObligationsTest` → `OutboxObligationsTest`,
  `HandoffObligationsPropertyTest` → `OutboxObligationsPropertyTest`, `InTransactionHandoffTest` →
  `InTransactionOutboxTest`; update identifiers in `DuplicateEventTest`, `SagaEngineHappyPathTest`, `DiagnosticsTest`,
  `internal/EngineCommandGatewayTest`, `support/TestDoublesTest`, `support/DifferentialRun`, `FailurePositionTest`,
  `TestTransportEndToEndTest`, `fixtures/idempotency/IdempotencyFixture`, `fixtures/compensation/DeliveryFixture`,
  `fixtures/failure/FailureFixture`, `HandlingReportOutcomesTest`, `ReentrancyTest`, `OutsideUnitOfWorkTest`, and in
  `saga-forge-example` if it references them. Afterwards
  `grep -rE 'Handoff|HandedOff|handOff' --include=*.java saga-forge-*` returns nothing.
- [X] T008 [P] Rename in living docs: `docs/architecture.md`, `README.md`, `AGENTS.md` use the new names. Do not edit
  specs 004 to 006; instead add at the top of `specs/004-saga-engine/spec.md` and
  `specs/004-saga-engine/contracts/event-handoff.md` a dated note: "Renamed in 007 (research §14): EventHandoff →
  Outbox, handOff → store, HandedOffEvents → OutgoingMessages, DispatchingEventHandoff → ImmediateDispatchOutbox,
  Builder.handoff → outbox, handoffFailures → outboxFailures." Update `.specify/memory/memory.md` entries G-ENG-07 and
  G-ENG-12 to the new names.
- [X] T009 Checkpoint (rename): `mvn -B -pl saga-forge-core,saga-forge-engine,saga-forge-storage-postgres,saga-forge-example -am clean verify`
  green with the same test count as before the rename.

### Core contracts ([contracts/core-spi.md](./contracts/core-spi.md))

- [X] T010 [P] Create `core/annotation/CommandDelivery.java` (`IN_UNIT_OF_WORK`, `OUTBOX`, Javadoc per contract) and add
  `CommandDelivery delivery() default CommandDelivery.IN_UNIT_OF_WORK` to `core/annotation/Command.java`.
- [X] T011 [P] Create `core/spi/OutgoingCommand.java` (record `CommandId commandId, Object command`, both non-null,
  `IllegalArgumentException` otherwise).
- [X] T012 Extend `core/spi/OutgoingMessages.java` with `List<OutgoingCommand> commands`: canonical 5-argument constructor;
  keep the 4-argument constructor (no commands); unmodifiable copies, no null elements; at least one of `events` and
  `commands` non-empty. Extend `core-test/spi/OutgoingMessagesTest.java`.
- [X] T013 Extend `core/spi/Outbox.java` with `default boolean supportsCommands()` (false) and
  `default void committed(OutgoingMessages batch)` (no-op), Javadoc per contract.
- [X] T014 [P] Create `core/spi/OutboxCommand.java` (record `CommandId commandId, Object command,
  Consumer<LocalTransaction> inTransaction`, all non-null) and `core/spi/CommandReceiver.java` (`void receive(OutboxCommand)`,
  Javadoc per contract).
- [X] T015 Create package `core/spi/store/` with `package-info.java` and, per [data-model.md](./data-model.md):
  `MessageKind`, `MessageStatus`, `DeliveryFailure` (exception class, message truncated to 4,000 chars, time; factory
  `of(Throwable, Instant)`), `NewMessage` (validation: `commandId` present iff `COMMAND`; source saga type/id both or
  neither), `StoredMessage` (invariants from data-model.md: lease fields both or neither, `consumedAt` iff `CONSUMED`,
  `setAsideReason` iff `SET_ASIDE`, `payload` optional), `MessageQuery` (builder; limit 1 to 1,000, default 100),
  `ConsumerHeartbeat`, exceptions `DuplicateMessageException`, `LeaseLostException`, `UnreadableMessageException`
  (carries the `StoredMessage` without payload), and the `MessageStore` interface exactly as in the contract.
- [X] T016 [P] Write `core-test/spi/log/StoreRecordsTest.java` covering every validation rule of T015, and
  `core-test/annotation/CommandDeliveryTest.java` (default is `IN_UNIT_OF_WORK`).

### Engine hook and test doubles

- [X] T017 Call `Outbox.committed(batch)` in the engine after a successful commit of every saga-scoped and non-saga unit of
  work whose outbox timing is `IN_TRANSACTION`, after the instance lock was released and with no engine transaction
  active (`engine/internal/SagaUnitOfWork.java`, `NonSagaUnitOfWork.java`, `CompensationUnitOfWork.java` where they store
  in-transaction); a `RuntimeException` is logged through `Diagnostics` (new `outboxCommittedFailed`) and ignored. Never
  on rollback.
- [X] T018 [P] Extend `engine-test/support/CapturingOutbox.java` to record `committed` calls and `supportsCommands`;
  write `engine-test/OutboxCommittedTest.java`: called once after commit for `IN_TRANSACTION`, not on rollback, not for
  `AFTER_COMMIT`, a throwing `committed` changes nothing.
- [X] T019 Create `transport-test/support/InMemoryMessageStore.java`: a thread-safe `MessageStore` double that joins the
  caller's `LocalTransaction` from `engine-test/support/InMemoryTransactionBoundary` (entries appended in a transaction
  become visible only on its commit, vanish on rollback), implements every lease invariant of data-model.md, stores the
  payload object as is, and offers test hooks `failNextAppend()`, `crashAfter(String operation)` and
  `markUnreadable(messageId)`.
- [X] T020 [P] Write `transport-test/support/InMemoryMessageStoreTest.java`: append visibility on commit/rollback, lease
  ordering and head-of-key blocking, lease expiry, lease-lost writes rejected, set-aside does not block, redeliver,
  purge.
- [X] T021 Checkpoint: `mvn -B -pl saga-forge-core,saga-forge-engine,saga-forge-transport-inmemory -am clean verify`.

---

## Phase 3: User Story 1 — Events between modules are delivered in memory (P1) 🎯 MVP

**Goal**: an event committed by one unit of work reaches the sagas that react to it, after commit, with no broker.

**Independent Test**: canonical order saga over the transport and `InMemoryMessageStore`; `OrderCreatedEvent` published
outside a unit of work; saga ends `COMPLETED` after `PaymentProcessedEvent` (quickstart scenario 1, 2).

- [X] T022 [P] [US1] Create `transport/TransportSettings.java` (record: workers 4, maxAttempts 10, backoff initial 1 s
  and max 1 min, lease 5 min, poll 500 ms, lease batch 100, retention 7 d, sweep 1 h, shutdown default 30 s; validation
  of positive values) per [contracts/in-memory-transport.md](./contracts/in-memory-transport.md).
- [X] T023 [P] [US1] Create `transport/internal/OrderingKeys.java`: computes `orderingKey` per research §5 by reading
  `@DomainEvent(aggregate)` and the event's `@AggregateIdentifier` field (`String.valueOf`); for commands, the command's
  `@AggregateIdentifier` value with its aggregate type if present, else `saga:` + `CommandId.sagaId()`, else null.
  Reflection results cached per class.
- [X] T024 [US1] Create `transport/internal/LogAppender.java`: maps an `OutgoingMessages` batch to `NewMessage`s (event
  message id `unitOfWorkId + ":" + index`, commands after events with their `CommandId.value()`), and a single event
  published outside a unit of work to a `NewMessage` with a random UUID id.
- [X] T025 [US1] Create `transport/internal/Relay.java`: a coordinator thread waiting on a signal or the poll interval,
  leasing batches through `MessageStore.lease`, dispatching each to a `KeyedWorkers` pool (same key → same worker, null
  key → any), delivering an `EVENT` entry to `EventReceiver.receive` then `markConsumed` in its own transaction;
  `signal()` is non-blocking. Failure handling is deferred to US2 (here: any exception releases nothing and is logged).
- [X] T026 [US1] Create `transport/internal/KeyedWorkers.java` over a host-supplied `Executor` or an internal fixed pool
  of `workers` daemon threads named `saga-forge-transport-N`.
- [X] T027 [US1] Create `transport/InMemoryTransport.java` with its `Builder` (contract): `dispatcher()` returning an
  `EventDispatcher` (append in the active transaction if any, else in a new one, then `relay.signal()`; null event →
  `IllegalArgumentException`; `registerSagaType` no-op), `outbox()` returning an `Outbox` (`IN_TRANSACTION`,
  `supportsCommands()` true, `store` appends via `LogAppender`, `committed` signals), `start(EventReceiver,
  CommandReceiver)` once only, `stop(Duration)` idempotent; `System.Logger` INFO on start/stop.
- [X] T028 [P] [US1] Create `transport/package-info.java` documenting the delivery guarantee (at least once), the single
  instance assumption and the assembly order of the contract.
- [X] T029 [P] [US1] Create fixtures `transport-test/fixtures/OrderModulith.java`: the canonical order saga, `Order`
  aggregate and `PaymentService` copied from `saga-forge-example` in a test package (the example module is not a
  dependency), plus `transport-test/support/TransportHarness.java` assembling engine + transport + `InMemoryMessageStore`
  + `TransactionalInMemoryStorageProvider` per the contract's assembly snippet, with `awaitQuiet(Duration)`.
- [X] T030 [US1] Write `transport-test/EventDeliveryTest.java`: US1 acceptance scenarios 1 to 4 (start outside a unit of
  work; cross-instance event after commit; rollback delivers nothing; unreacted event consumed once), delivery never on
  the recording thread (FR-009), recording order within one unit of work (FR-007), failed append reaches the caller
  (FR-002).
- [X] T031 [P] [US1] Write `transport-test/internal/OrderingKeysTest.java` (event, command with and without aggregate id,
  saga-scoped and unscoped command).
- [X] T032 [US1] Write `transport-test/DeliveryLatencyTest.java`: on an idle harness, 1,000 sequential commits are each
  delivered within 100 ms in at least 99% of cases (SC-003; tag `@Tag("slow")` if over 10 s).
- [X] T033 [US1] Checkpoint: `mvn -B -pl saga-forge-transport-inmemory -am clean verify`.

---

## Phase 4: User Story 2 — Unconsumed messages survive a restart (P1)

**Goal**: every unconsumed entry is delivered after a restart or a failure, in per-key order, with bounded retries.

**Independent Test**: commit, stop the relay before delivery, start a new transport over the same log; the saga ends
`COMPLETED` exactly once (quickstart scenarios 3, 4, 7).

- [X] T034 [US2] Create `transport/internal/Backoff.java` (1 s doubling, capped at max; pure function of attempts) and
  extend `Relay.java`: on a receiver exception (including `EventHandlingException`), `recordFailure` with
  `DeliveryFailure.of(e)` and `nextAttemptAt = now + backoff(attempts)`; at `attempts >= maxAttempts`, `setAside(...,
  "MAX_ATTEMPTS")` with a WARNING; `UnreadableMessageException` from `lease` → `setAside(..., "UNREADABLE: " + detail)`.
  `LeaseLostException` on any write → WARNING, result dropped.
- [X] T035 [US2] Add lease renewal to `Relay.java`: while a delivery runs longer than half the lease, renew it; a
  delivery whose renewal reports the id lost finishes but its result write is dropped.
- [X] T036 [US2] Implement restart and shutdown in `InMemoryTransport.java`/`Relay.java`: `start` leases immediately
  (FR-018); `stop(timeout)` stops leasing, waits for running deliveries up to `timeout`, then `MessageStore.release`
  (FR-020).
- [X] T037 [P] [US2] Create `transport/internal/Heartbeat.java`: random consumer id, upsert every 30 s, WARNING at start
  if `liveConsumers(now - 90 s)` lists another consumer (FR-021); used as the relay's `consumerId`.
- [X] T038 [US2] Write `transport-test/RestartTest.java`: US2 acceptance scenarios 1 to 4 using two transports over one
  `InMemoryMessageStore` (first one abandoned without `stop`, leases expired by a fake clock); scenario 3 asserts
  `HandlingOutcome.DUPLICATE` and one step run.
- [X] T039 [P] [US2] Write `transport-test/FailureHandlingTest.java`: backoff schedule, set-aside at the 10th failure
  (SC-006), other keys keep flowing while one key retries (FR-016), set-aside unblocks its key, unreadable payload set
  aside at once, `FAILED`/`COMPENSATED`/`REQUIRES_INTERVENTION` outcomes consumed (FR-017).
- [X] T040 [P] [US2] Write `transport-test/ShutdownTest.java` (running delivery finishes or stays unconsumed; leases
  released) and `transport-test/SecondInstanceTest.java` (WARNING logged, no entry lost, quickstart scenario 13).
- [X] T041 [US2] Write `transport-test/OrderingPropertyTest.java` (jqwik): random interleavings of entries over a few keys,
  random failures and restarts; per key, consumption order equals position order among non-set-aside entries, and every
  committed entry ends `CONSUMED` or `SET_ASIDE`.
- [X] T042 [US2] Checkpoint: `mvn -B -pl saga-forge-transport-inmemory -am clean verify`.

---

## Phase 5: Postgres message store

**Purpose**: the durable log for real restarts ([contracts/postgres-message-log.md](./contracts/postgres-message-log.md)).

- [X] T043 Extend `postgres-res/schema.sql` with `saga_message_store`, `saga_message_store_consumer` and the four indexes
  exactly as [data-model.md](./data-model.md); create `postgres-res/schema-upgrade-007.sql` (the same DDL, guarded with
  `IF NOT EXISTS`).
- [X] T044 Create `postgres/PostgresMessageStore.java` (row ↔ `StoredMessage` mapping, `CommandId` and `DeliveryFailure` as JSONB
  via `JsonCodec`, payload decoding with the configured class loader; decode failure → `UnreadableMessageException`).
- [X] T045 Create `postgres/PostgresMessageStore.java` implementing `MessageStore` per contract: batched append (unique
  violation → `DuplicateMessageException`), single-statement lease with `FOR UPDATE SKIP LOCKED` and the head-of-key
  `NOT EXISTS`, leaseholder writes with `leased_by` guard (`LeaseLostException`), `release`, `query`, `redeliver`,
  `purgeConsumed`, `heartbeat`, `liveConsumers`, and public `appendDeliveryRecord(LocalTransaction, NewMessage,
  String externalRef)` for interop; SQL errors → `UncheckedSqlException`.
- [X] T046 [P] Extend `postgres-test/SchemaTest.java` for the new tables, columns, checks and indexes, and for
  `schema-upgrade-007.sql` on a 006 schema (container).
- [X] T047 Create `transport-test/support/MessageStoreContract.java` (in the transport `test-jar`): an abstract JUnit
  contract suite over any `MessageStore` (the invariants of T020), and make `InMemoryMessageStoreTest` extend it.
- [X] T048 Write `postgres-test/PostgresMessageStoreContainerTest.java` (`@Tag("container")`) extending
  `MessageStoreContract`, plus Postgres-only cases: `SKIP LOCKED` between two concurrent consumers, payload round trip for
  the example's events and commands (FR-004), unreadable class set aside.
- [X] T049 Write `postgres-test/TransportFaultInjectionContainerTest.java` (`@Tag("container")`): the order saga over
  engine + transport + `PostgresStorageProvider` + `PostgresMessageStore`; stop at each boundary (after commit, after
  lease, after `receive` before `markConsumed`, during `stop`) and start a fresh transport on the same database; assert
  SC-001 (all delivered, no step twice) and SC-002 (no rolled-back entry ever written).
- [X] T050 Write `postgres-test/RestartBacklogContainerTest.java` (`@Tag("container")`): 10,000 unconsumed entries,
  restart, all consumed and a new saga accepted within 60 s (SC-005).
- [X] T051 Checkpoint: `mvn -B -pl saga-forge-storage-postgres,saga-forge-transport-inmemory -am clean verify` (unit
  suites only; T046, T048 to T050 compile).

---

## Phase 6: User Story 3 — Commands travel through the same log (P2)

**Goal**: commands whose type opts in are logged with the sender's unit of work and handled once after commit, with
their original `CommandId`; other commands behave as today.

**Independent Test**: `ProcessPaymentCommand` marked `OUTBOX`; crash after the step commits; after restart the handler
runs once with the same `CommandId` (quickstart scenarios 5, 6).

- [X] T052 [US3] Implement R23 in `core/scanner/ReflectiveAnnotationScanner.java` (`validateOutboxCommands` in
  `validateAll`), message per T001; extend `core-test/scanner/ValidationReportTest.java` with a passing and a failing
  model.
- [X] T053 [US3] In `engine/internal/EngineCommandGateway.java`: for a `OUTBOX` command inside a unit of work, resolve the
  handler route first (`NoCommandHandlerException` as today if none), assign the `CommandId` as today, skip it if already handled, otherwise buffer it on the unit of work
  (`UnitOfWork.recordOutboxCommand(OutgoingCommand)`) without running the handler; outside a unit of work, open the
  non-saga unit of work and buffer it there (FR-010a). `IN_UNIT_OF_WORK` commands unchanged.
- [X] T054 [US3] In `engine/internal/UnitOfWork.java`, `SagaUnitOfWork.java`, `NonSagaUnitOfWork.java`: carry buffered
  commands into the `OutgoingMessages` batch in send order; discard them on rollback; on commit add each outbox command's
  `CommandId` to the sending step's `StepRecord.handledCommands` (research §3).
- [X] T055 [US3] Implement `CommandReceiver` in the engine (`EngineCommandGateway.receive`, exposed by
  `SagaEngine.commandReceiver()`): new non-saga unit of work, `CommandIds.withBound(delivery.commandId(), …)`, run the
  route, call `delivery.inTransaction()` with the current `LocalTransaction` before commit; reentrancy and not-started
  rules as `EventReceiver`.
- [X] T056 [US3] In `engine/internal/StartupChecks.java`: fail `start()` with `SagaEngineStartupException` if the
  registered model has a `OUTBOX` command type and the outbox's `supportsCommands()` is false.
- [X] T057 [US3] Extend `transport/internal/LogAppender.java` and `Relay.java` for `COMMAND` entries: deliver through
  `CommandReceiver.receive(new OutboxCommand(id, command, tx -> log.markConsumed(tx, consumerId, messageId, now)))`;
  failures as in T034.
- [X] T058 [P] [US3] Write `engine-test/OutboxCommandTest.java`: not handled before commit, never on rollback, handled
  via `CommandReceiver` with the original `CommandId`, events the handler records go to the outbox, an `IN_UNIT_OF_WORK`
  command whose handler throws still rolls the step back and retries/compensates (US3 scenario 5), startup refusal of
  T056, retried step does not log the command twice.
- [X] T059 [P] [US3] Write `engine-test/OutboxCommandPropertyTest.java` (jqwik, Property 35) over random step outcomes
  (commit, rollback, retry) and redeliveries.
- [X] T060 [US3] Write `transport-test/CommandDeliveryTest.java`: US3 acceptance scenarios 1 to 4, including a crash
  right after the handler's commit (the consumed mark is in the same transaction, so no second run), and a
  crash before the handler's commit (handler runs once on redelivery with the same `CommandId`).
- [X] T061 [US3] Checkpoint: `mvn -B -pl saga-forge-core,saga-forge-engine,saga-forge-transport-inmemory -am clean verify`.

---

## Phase 7: User Story 4 — The operator can see what the store holds (P3)

**Goal**: query the store by status and saga, redeliver set-aside entries, and keep consumed entries 7 days.

**Independent Test**: one consumed, one unconsumed and one set-aside entry are reported with status, type, attempts and
last failure; the set-aside one can be redelivered (quickstart scenarios 8, 10).

- [X] T062 [US4] Create `transport/MessageStoreQueries.java` (`find(MessageQuery)`, `redeliver(messageId)` → boolean plus
  `relay.signal()`, `status()` with counts per status and the oldest unconsumed age) and expose it as
  `InMemoryTransport.queries()`.
- [X] T063 [US4] Create `transport/internal/RetentionSweeper.java`: every sweep interval, `purgeConsumed(now - retention,
  1000)` until fewer than 1,000 are deleted; never touches other statuses (FR-019); started and stopped with the
  transport.
- [X] T064 [US4] Add `InMemoryTransport.onDelivered(Consumer<StoredMessage>)`, called after an event entry is consumed;
  exceptions logged and ignored (FR-027, used by T070).
- [X] T065 [P] [US4] Write `transport-test/OperatorQueriesTest.java` (US4 scenarios 1 and 2, SC-007) and
  `transport-test/RetentionTest.java` (fake clock: consumed older than 7 days removed, set-aside and unconsumed kept).
- [X] T066 [US4] Checkpoint: `mvn -B -pl saga-forge-transport-inmemory -am clean verify`.

---

## Phase 8: Spring wiring and Spring Modulith interop

**Purpose**: FR-026 to FR-029, SC-008 ([contracts/spring-wiring.md](./contracts/spring-wiring.md)).

- [X] T067 Spike risk R1 first: write `spring-test/modulith/RegistryLookupSpikeContainerTest.java` (`@Tag("container")`)
  storing a `SagaForgeEnvelope` through `EventPublicationRegistry.store` in a Spring transaction and completing it with
  `markCompleted(envelope, TARGET)` after a JVM-level round trip (fresh deserialized instance). Record the expected
  outcome and the fallback in G-007-05; T071 implements whichever path the spike's assertions encode. Hand to the user
  with T082 if it cannot be run here.
- [X] T068 Create `spring/SpringTransactionBoundary.java` (`PROPAGATION_REQUIRED`, joins an active Spring transaction,
  `resource(Connection.class)` via `DataSourceUtils`) and `spring-test/SpringTransactionBoundaryTest.java` with a
  `DataSourceTransactionManager` over a JDBC fake modelled on `postgres-test/JdbcFake.java` (no container).
- [X] T069 Create `spring/transport/InMemoryTransportConfiguration.java` (`@AutoConfiguration`, conditional on
  `InMemoryTransport` on the classpath and a `DataSource`), `spring/transport/TransportProperties.java`
  (`@ConfigurationProperties("saga-forge.transport")` mapping to `TransportSettings`), `spring/transport/
  TransportLifecycle.java` (`SmartLifecycle`, phase after the `SagaEngine` bean's start), and register them in
  `spring-res/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`. The configuration builds
  the transport only; the host supplies the `SagaEngine` bean built with `transport.dispatcher()`/`outbox()` (FR-029).
- [X] T070 [P] Create `spring/transport/ApplicationEventBridge.java` (on unless
  `saga-forge.transport.publish-application-events=false`): `transport.onDelivered` re-publishes the payload through
  `ApplicationEventPublisher` outside any transaction, listener failures logged and ignored.
- [X] T071 Create `spring/modulith/SagaForgeEnvelope.java`, `spring/modulith/ModulithMessageStore.java` (decorator per
  contract and research §10, using the path T067 settled) and `spring/modulith/ModulithInteropConfiguration.java`
  (conditional on an `EventPublicationRegistry` bean and `saga-forge.transport.modulith-interop` not `false`; INFO when
  `republish-outstanding-events-on-restart` is on).
- [X] T072 Create `transport-test/support/TransportAcceptanceSuite.java` (in the transport `test-jar`): an abstract suite
  of US1 to US4 acceptance scenarios against an abstract harness factory; make the plain-Java tests of T030, T038, T060
  and T065 run through it with `TransportHarness` (refactor, same assertions).
- [X] T073 Write `spring-test/SpringOwnLogAcceptanceContainerTest.java` and
  `spring-test/modulith/ModulithInteropAcceptanceContainerTest.java` (`@Tag("container")`, Spring Boot test context,
  Testcontainers Postgres, the canonical order saga) extending `TransportAcceptanceSuite` (SC-008); the interop test also
  asserts delivery records have null payload and registry publications are completed (FR-028a).
- [X] T074 [P] Write `spring-test/ApplicationEventBridgeTest.java` and
  `spring-test/transport/InMemoryTransportConfigurationTest.java` (`ApplicationContextRunner`: beans present/absent per
  conditions, properties bound, interop off by property).
- [X] T075 Checkpoint: `mvn -B -pl saga-forge-spring-boot-starter -am clean verify` (unit suites; container classes compile).

---

## Phase 9: Polish & cross-cutting

- [X] T076 [P] Update `saga-forge-example`: mark `ProcessPaymentCommand` `@Command(delivery = CommandDelivery.OUTBOX)` only
  if `OrderProcessingSagaEngineTest` keeps its current expectations, otherwise leave it and add a second, outbox command
  example in a comment; add `example-test/OrderProcessingSagaTransportTest.java` running the saga end to end over
  `InMemoryTransport` and the `InMemoryMessageStore` test-jar double (SC-004). Keep the example in sync with AGENTS.md rules
  (`associationProperty`, start/end annotations shown).
- [X] T077 [P] Update `docs/architecture.md`: Current status table (transport in-memory implemented), module
  responsibilities for `saga-forge-transport-inmemory` (outbox + relay, no Spring; supersedes "wraps
  `ApplicationEventPublisher`") and the starter (transport wiring, Modulith interop), the at-least-once guarantee and the
  closing of G-ENG-07 for hosts using it.
- [X] T078 [P] Update `README.md` (transport assembly snippet from the contract, `CommandDelivery.OUTBOX` example) and
  `docs/use-cases.md` (a restart scenario).
- [X] T079 [P] Update `AGENTS.md`: Current Status bullet for 007, Module Map entries for the transport and starter, Saga
  Model note on `@Command(delivery = OUTBOX)`, Known Gaps line for G-007-01 to G-007-06.
- [X] T080 Self-check: every FR-001 to FR-029 and SC-001 to SC-008 maps to a completed task or a recorded gap; note any
  uncovered item in T081. Mark G-ENG-07 in memory.md as closed for hosts using the in-memory transport.
- [X] T081 Fill the memory.md implementation notes from T002 (what changed per module, test counts, any deviation, the
  R1 spike result) and update `specs/007-in-memory-transport/spec.md` **Status** to "Implemented (date)".
- [X] T082 Hand off to the user: list every `@Tag("container")` class added or changed (T046, T048, T049, T050, T067,
  T073) with the command
  `mvn -B -Pcontainer-tests -pl saga-forge-storage-postgres,saga-forge-spring-boot-starter -am verify`, and state which
  results are unverified until it runs.

---

## Dependencies & Execution Order

| Phase | Tasks | Depends on | Notes |
|---|---|---|---|
| 1. Setup | T001–T004 | — | T001 before any scanner/engine change (Principle III) |
| 2. Foundational | T005–T021 | Phase 1 | rename T005–T009 strictly first and alone |
| 3. US1 (MVP) | T022–T033 | Phase 2 | |
| 4. US2 | T034–T042 | US1 | |
| 5. Postgres | T043–T051 | Phase 2 (T015); T049/T050 need US2 | can run beside US1/US2 up to T048 |
| 6. US3 | T052–T061 | US2 (failure handling) | T052–T056 need only Phase 2 |
| 7. US4 | T062–T066 | US2 | independent of US3 |
| 8. Spring | T067–T075 | US1–US4, Postgres | T067 spike and T068 can start after Phase 2 |
| 9. Polish | T076–T082 | all | |

### Story completion order

```text
Setup → Foundational (rename → contracts → hook/doubles) → US1 → US2 ─┬─ US3 ─┬─ Spring → Polish
                                              └── Postgres ─────────┤  US4 ─┘
```

## Parallel Examples

| Phase | Parallel tasks |
|---|---|
| Phase 1 | T002, T003, T004 together after T001 |
| Phase 2 | after T009: T010, T011, T014 together; T016, T018, T020 together |
| US1 | T022, T023, T028, T029 together, then T024 to T027; T031 beside T030 |
| US2 | T037 beside T034 to T036; T039 and T040 together |
| US3 | T058 and T059 together |
| Postgres vs stories | a second implementer takes T043 to T048 while another works through US1 and US2 |
| Spring | T067 (spike) and T068 early; T070 and T074 beside T071 |

## Implementation Strategy

### MVP (US1)

Phases 1 to 3: the rename, the contracts, and events delivered in memory after commit over the store double. It proves
the outbox and relay shape end to end; durability across real restarts follows with US2 and Postgres.

### Incremental delivery

1. MVP (US1).
2. US2: failures, retries, set-aside, restart.
3. Postgres: the real durable log, fault injection, backlog restart.
4. US3: outbox commands.
5. US4: operator queries and retention.
6. Spring wiring and Modulith interop.
7. Polish.

Each checkpoint leaves a green build, so work can stop after any story if priorities change.
