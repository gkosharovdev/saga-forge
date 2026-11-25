---

description: "Task list for 017-saga-deadlines"
---

# Tasks: Saga Deadlines

**Input**: Design documents from `/specs/017-saga-deadlines/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md),
[contracts/](contracts/), [quickstart.md](quickstart.md)

**Tests**: included. Every story in the spec has an independent test, the quickstart names the suites, and AGENTS.md
asks for a test with every new abstraction, written with its first caller (same task or the one right before it).

**Organization**: setup; then the foundational phase (core vocabulary, message-store scheduling, the outbox cancel path,
Postgres columns, scanner rules), which every story needs. Then the stories in spec order: US1 builds the whole
deadline path for one deadline that compensates; US2 adds several deadlines and context/property durations; US3 hardens
the "never after RUNNING" guarantees; US4 exposes `CommandGateway.schedule/cancel`; US5 adds startup rejections. The
final phases update the **examples** and the **README and docs** (requested explicitly), then verify.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an unfinished task)
- **[Story]**: the user story from spec.md (US1 to US5)

## Path Conventions

`…` stands for `src/main/java/io/github/gkosharovdev/sagaforge` and `…test` for
`src/test/java/io/github/gkosharovdev/sagaforge` inside the named module. Other paths are relative to the repository
root. `internal/` under the runtime means `saga-forge-runtime/…/runtime/internal/`; under the transport,
`saga-forge-transport-inmemory/…/transport/inmemory/internal/`.

---

## Phase 1: Setup

**Purpose**: a branch, a green baseline and the design skills.

- [X] T001 Create branch `017-saga-deadlines` from `master`; run `mvn -q install` from the repository root and confirm it is green before any change
- [X] T002 Load the `effective-java`, `clean-code-java` and `solid-design-review` skills (AGENTS.md) and keep them applied to every type added below; read `specs/017-saga-deadlines/research.md` R1–R18 and the five files in `specs/017-saga-deadlines/contracts/`

---

## Phase 2: Foundational (blocking prerequisites)

**Purpose**: the vocabulary and storage every story builds on. No runtime behaviour yet.

### Core vocabulary

- [X] T003 [P] Add `saga-forge-core/…/core/deadline/DeadlineReached.java`, `OpenDeadline.java`, `DeadlineDurationException.java` (extends `IllegalStateException`, implements `core.failure.FinalFailure`) and `package-info.java` per data-model.md, with validating canonical constructors; tests in `saga-forge-core/…test/core/deadline/DeadlineValuesTest.java`
- [X] T004 [P] Add `saga-forge-core/…/core/annotation/StartDeadline.java` (repeatable via `StartDeadlines.java`; `name`, `duration`, `durationFrom`, `cancelledBy`) and `DeadlineExpired.java` (`value`); give `SagaStep.associationProperty()` `default ""` and update its Javadoc (event steps still require it) in `saga-forge-core/…/core/annotation/SagaStep.java`
- [X] T005 [P] Add `saga-forge-core/…/core/command/ScheduledCommandId.java` (record, non-blank `value`) and default methods `schedule(Object, Instant)` / `cancel(ScheduledCommandId)` throwing `UnsupportedOperationException` to `saga-forge-core/…/core/command/CommandGateway.java`
- [X] T006 [P] Add `CompensationTriggerKind.DEADLINE_EXPIRED` and a nullable `deadlineName` to `saga-forge-core/…/core/compensation/CompensationTrigger.java` and `saga-forge-core/…/core/outcome/OutcomeTrigger.java` (non-null iff `DEADLINE_EXPIRED`; for it failure fields null, `eventType` = `DeadlineReached` FQN), keeping the old constructors; extend `CompensationTriggerTest` and `OutcomeTriggerTest` in `saga-forge-core/…test`
- [X] T007 [P] ~~Add `DEADLINE_STARTED`, `DEADLINE_CANCELLED`, `DEADLINE_EXPIRED` to `saga-forge-core/…/core/spi/SagaLifecycleEventKind.java`~~ Dropped during implementation: a lifecycle entry would need a deadline name threaded through `SagaLifecycleEntry` and Postgres history; open deadlines live on the instance, and diagnostics plus counters record starts, cancels and expiries (memory.md G-017-06)
- [X] T008 Add `Map<String, OpenDeadline> openDeadlines` to `saga-forge-core/…/core/spi/SagaInstance.java` (unmodifiable copy; must be empty unless `CREATED`/`RUNNING`; old constructor passes an empty map); extend `SagaInstanceTest` in `saga-forge-core/…test/core/spi/`
- [X] T009 [P] Add `saga-forge-core/…/core/spi/ScheduledMessage.java` per data-model.md; add `List<ScheduledMessage> scheduled` to `saga-forge-core/…/core/spi/OutgoingMessages.java` (a batch may hold only scheduled messages; old constructors pass empty); add `default boolean supportsScheduling()` (false) and `default Set<String> cancel(Collection<String>)` (throws `UnsupportedOperationException`) to `saga-forge-core/…/core/spi/Outbox.java`, documented per contracts/outbox-and-gateway.md; tests in `OutgoingMessagesTest` and `ScheduledMessageTest`

### Message-store scheduling (contracts/message-store-scheduling.md)

- [X] T010 Add nullable `notBefore` to `saga-forge-core/…/core/spi/store/NewMessage.java` (non-null requires `orderingKey == null`, research R2) and `StoredMessage.java`, keeping old constructors; add `MessageStatus.CANCELLED`; add `default boolean supportsScheduling()` and `default Set<String> cancel(LocalTransaction, Collection<String>, Instant)` to `MessageStore.java`, with the leasable rule and cancel table in its Javadoc
- [X] T011 Add scheduling cases to `MessageStoreContract` in `saga-forge-transport-inmemory/…test/transport/inmemory/MessageStoreContract.java`: not leased before `notBefore`, leased after, rollback stores nothing, `orderingKey` + `notBefore` rejected, cancel table (pending, leased, consumed, set aside, cancelled, unknown), cancelled never leased and does not block a key, leaseholder writes on a cancelled message throw `LeaseLostException`, query by `CANCELLED`, retention removes `CANCELLED`
- [X] T012 Implement scheduling in `saga-forge-transport-inmemory/…test/transport/inmemory/InMemoryMessageStore.java` (`nextAttemptAt = notBefore` on append, `cancel`, `supportsScheduling() = true`) until T011 passes
- [X] T013 [P] Postgres schema: add `not_before`, the `CANCELLED` status, `saga_instance.open_deadlines jsonb NOT NULL DEFAULT '{}'` and the three lifecycle kinds to `saga-forge-storage-postgres/src/main/resources/schema.sql`, and the same as `ALTER`s in new `saga-forge-storage-postgres/src/main/resources/schema-upgrade-017.sql` (constraint names from the current schema); extend the schema-upgrade test that applies earlier upgrade scripts
- [X] T014 Implement scheduling in `saga-forge-storage-postgres/…/storage/postgres/PostgresMessageStore.java` (`next_attempt_at = not_before`, `cancel` as one `UPDATE … RETURNING`, `supportsScheduling() = true`) until the Postgres run of `MessageStoreContract` passes (`-Pcontainer-tests`)
- [X] T015 Persist `SagaInstance.openDeadlines` as JSON in `saga-forge-storage-postgres/…/storage/postgres/PostgresStorageProvider.java` (and its codec), and the trigger's `deadlineName` in the compensation-trigger JSON; extend the Postgres storage provider contract test with a round-trip of open deadlines and a `DEADLINE_EXPIRED` trigger
- [X] T016 [P] Make the starter's `ModulithMessageStore` in `saga-forge-spring-boot-starter/…/springboot/…/ModulithMessageStore.java` report `supportsScheduling() = false` and reject a `NewMessage` with `notBefore` (`UnsupportedOperationException`); unit test beside its existing tests

### Scanner rules (contracts/deadline-annotations.md)

- [X] T017 Implement R24–R29 in `saga-forge-core/…/core/scanner/ReflectiveAnnotationScanner.java` (run in `validate(Class<?>)` with R19–R22), with the exact error texts of the contract; scope Property 10, Property 11 and the non-blank `associationProperty` rule to steps without `@DeadlineExpired`; let `@SagaCompensation(forStep)` target a non-`@StartCompensation` timeout step
- [X] T018 Add `saga-forge-core/…test/core/scanner/DeadlineValidationTest.java` with one invalid-model test per rule and message (fixtures under `…test/core/scanner/fixtures/deadline/`) and a valid saga with two deadlines; Property 40 is covered by `DeadlineValidationTest`'s valid two-deadline saga and per-rule cases (no separate property test)
- [X] T019 Add R24–R29, Properties 40–43, the amended Properties 10/11 and the error-table rows to `.kiro/specs/saga-forge-framework/design.md`; `docs/guide/validation-rules.md` is updated with the docs in T050

**Checkpoint**: core compiles with all new types, both stores pass the scheduling contract, the scanner validates deadline models.

---

## Phase 3: User Story 1 — A saga times out and compensates (Priority: P1) 🎯 MVP

**Goal**: one deadline with a fixed duration opens when its step commits, fires once after it is due, and its
`@StartCompensation` timeout step compensates with trigger `DEADLINE_EXPIRED`; it survives a restart.

**Independent Test**: runtime test over the in-memory transport with a controllable clock: start, advance past due,
expect the timeout step once, compensations, `SagaCompensated` with `deadlineName`; and a Postgres restart test.

- [X] T020 [US1] Add `internal/DeadlineModel.java` (per saga: declarations by step, timeout step by name, cancelling steps; fixed durations parsed at startup, `${…}` left for US2) built in `SagaRuntime` startup from the scanned model; unit test `…test/runtime/internal/DeadlineModelTest.java`
- [X] T021 [US1] Add `internal/DeadlinePlan.java`: for one unit of work, given completed steps and the instance's open deadlines, computes deadlines to start (id `<sagaId>:deadline:<name>:<unitOfWorkId>`, `dueAt = clock + duration` truncated to ms), to cancel (`cancelledBy`, replaced, all on leaving `RUNNING`, the one whose timeout step ran) and the resulting `openDeadlines`; unit test `DeadlinePlanTest.java` covering every rule of contracts/runtime-deadlines.md "Starting, replacing, cancelling"
- [X] T022 [US1] Wire `DeadlinePlan` into `internal/UnitOfWork.java`: before the instance write, cancel through `Outbox.cancel(ids)` in the transaction, add `ScheduledMessage`s to the batch, write `openDeadlines` and lifecycle entries; include the `FAILED` write and the compensation-start write; keep `UnitOfWorkBoundaryTest` green
- [X] T023 [US1] Add `internal/DeadlineDelivery.java` and route `DeadlineReached` in the runtime's `EventReceiver.receive` (as outcome events are recognised): lock the instance, check `RUNNING` and matching open `deadlineId`, run the `@DeadlineExpired` step in a `forInstance` unit of work (no parameter or the `DeadlineReached`), passing `DEADLINE_EXPIRED` + `deadlineName` to the compensation start when the step is `@StartCompensation`; the outcome event carries the same trigger
- [X] T024 [US1] Implement the in-memory transport outbox side in `saga-forge-transport-inmemory/…/transport/inmemory/internal/` (the `IN_TRANSACTION` outbox): append `OutgoingMessages.scheduled` as `NewMessage`s with `notBefore`, no ordering key and the batch's trace context; implement `Outbox.cancel` over `MessageStore.cancel(transactionBoundary.current(), …)`; `supportsScheduling()` delegates to the store; tests in the transport's outbox test class
- [X] T025 [US1] Runtime test `saga-forge-runtime/…test/runtime/DeadlineExpiryTest.java` (runtime test-jar doubles + in-memory transport, controllable clock): not run before due, run once after, compensations newest first, `SagaCompensated` trigger `DEADLINE_EXPIRED`/`paymentWindow`, lifecycle entries, deadline closed; a cancelledBy step before due ⇒ no timeout step
- [X] T026 [US1] Telemetry per contracts/runtime-deadlines.md in `internal/SagaInstrumentation.java` (handle span attribute `saga_forge.deadline.name`, counter `saga_forge.deadlines`) and `saga-forge-transport-inmemory/…/internal/TransportInstrumentation.java` (`saga_forge.message.not_before`); extend the instrumentation tests
- [X] T027 [US1] Container test `saga-forge-transport-inmemory/…test/…/DeadlineRestartContainerTest.java` (or beside the 10k restart test in the Postgres module): start a deadline, stop the runtime and relay, restart after the due time, expect the timeout step exactly once (SC-001); add 10,000 overdue deadlines at restart, all delivered, none lost, each at most one timeout step (SC-004)

**Checkpoint**: US1 is a working MVP — a saga with one deadline times out and compensates, across a restart.

---

## Phase 4: User Story 2 — Several deadlines, durations from context and properties (Priority: P1)

**Goal**: more than one deadline per saga, `durationFrom` a `SagaContext` attribute, `duration = "${key[:default]}"`,
replacement of an already open deadline, a non-compensating timeout step.

**Independent Test**: runtime tests with two deadlines in one saga; scanner already covers the model (T018).

- [X] T028 [US2] Add `SagaRuntime.Builder.propertyResolver(Function<String, Optional<String>>)` in `saga-forge-runtime/…/runtime/SagaRuntime.java`; `DeadlineModel` resolves `${key[:default]}` at startup and fails with `SagaRuntimeStartupException` naming saga, deadline and value when unresolved or unparseable; tests in `DeadlineModelTest`
- [X] T029 [US2] Resolve `durationFrom` in `DeadlinePlan` from the instance's `SagaContext` after the step ran (ISO-8601 `String`); missing or bad ⇒ `DeadlineDurationException` (classified `FINAL`), failing the unit of work; tests for compensate-if-committed and `FAILED`-if-nothing-committed
- [X] T030 [US2] Runtime test `saga-forge-runtime/…test/runtime/MultipleDeadlinesTest.java`: deadlines `a` and `b` in one saga, `a` expires ⇒ only `a`'s step; `cancelledBy` of `a` leaves `b` open; restarting an open deadline replaces it (old id ignored on delivery, reason `REPLACED`); a non-compensating timeout step that re-arms its own deadline (R29-valid) keeps the instance `RUNNING`

**Checkpoint**: both checkout-story shapes (context duration, property duration, cancel while waiting on) work.

---

## Phase 5: User Story 3 — A deadline never acts on a saga that stopped running (Priority: P1)

**Goal**: Property 41 and 42 hold under races and lost cancels.

**Independent Test**: deliver a deadline message directly for instances in every non-`RUNNING` status and for cancelled ids; randomized interleavings.

- [X] T031 [US3] Runtime test `saga-forge-runtime/…test/runtime/DeadlineIgnoredTest.java`: deliver `DeadlineReached` for `COMPLETED`, `COMPENSATING`, `COMPENSATED`, `FAILED`, `COMPENSATION_FAILED` instances, a cancelled and an unknown deadline id, a missing instance ⇒ `IGNORED`, no write, `saga.deadline.ignored` with the right reason, counter `event=ignored`; a `COMPENSATING` instance is not compensated twice
- [X] T032 [US3] Verify every write that leaves `RUNNING` cancels all open deadlines (end, `@StartCompensation`, `FINAL`/`EXHAUSTED` compensation start, `FAILED` write, 012 command-failure reaction) in `saga-forge-runtime/…test/runtime/internal/DeadlineCancellationTest.java`; fix any path in `internal/UnitOfWork.java` callers that misses it
- [X] T033 [US3] Property test `saga-forge-runtime/…test/runtime/DeadlineInterleavingPropertyTest.java`: 1,000 randomized interleavings of the cancelling event, an `@EndSaga` event and the deadline delivery (including delivery of an already leased message after cancel) ⇒ the timeout step runs at most once and never after the instance left `RUNNING` (SC-002, Properties 41/42)

**Checkpoint**: late, duplicate and racing deliveries are harmless.

---

## Phase 6: User Story 4 — Schedule and cancel a command (Priority: P2)

**Goal**: `CommandGateway.schedule(command, at)` / `cancel(id)` for outbox commands.

**Independent Test**: transport tests: not handled before due, handled once after, cancelled never handled, rollback leaves nothing.

- [X] T034 [US4] Implement `schedule`/`cancel` in the runtime's production `CommandGateway` (`internal/` gateway class): require a current unit of work and `@Command(delivery = OUTBOX)`; assign `CommandId` at schedule time (saga-scoped from a step); id `<unitOfWorkId>:scheduled:<n>`; `cancel` of an id from the same unit of work removes it from the batch, otherwise calls `Outbox.cancel`; extend `EngineCommandGatewayTest` with every row of the table in contracts/outbox-and-gateway.md
- [X] T035 [US4] Transport acceptance cases in `saga-forge-transport-inmemory/…test/…/TransportAcceptanceSuite.java`: scheduled command handled once after due through `CommandReceiver`, not before; cancelled never; rollback stores nothing; a past `at` is due at once; a scheduled command sent from a saga step that fails `FINAL` reaches 012's failure feedback

**Checkpoint**: applications can schedule their own commands.

---

## Phase 7: User Story 5 — Misconfiguration fails at startup (Priority: P2)

**Goal**: clear startup failures under `ImmediateDispatchOutbox` and Modulith interop.

**Independent Test**: runtime builder test and starter `ApplicationContextRunner` test.

- [X] T036 [US5] Startup check in `saga-forge-runtime/…/runtime/SagaRuntime.java`: a model with any `@StartDeadline` and an outbox with `supportsScheduling() == false` ⇒ `SagaRuntimeStartupException` with the message in contracts/outbox-and-gateway.md; `schedule` under such an outbox ⇒ `UnsupportedOperationException`; tests in `saga-forge-runtime/…test/runtime/SagaRuntimeStartupTest.java`
- [X] T037 [US5] Starter: pass Spring's `Environment` as `propertyResolver`, give the runtime and `InMemoryTransport` the same `Clock` bean (backs off for an application `Clock`), and add `DeadlinesRequireOwnLogFailureAnalyzer` (registered in `META-INF/spring.factories`/the starter's analyzer list) for the Modulith case, in `saga-forge-spring-boot-starter/…/springboot/`; `ApplicationContextRunner` tests for the property, the shared clock and the analyzer text
- [X] T038 [US5] Document the deadline-related behaviour (shared clock, `${…}` resolution, Modulith rejection) in `saga-forge-spring-boot-starter/README.md`; keep `ReadmePropertiesTest` green

**Checkpoint**: every misconfiguration names the saga, the deadline and the fix.

---

## Phase 8: Test fixture (supports US1–US4)

- [X] T039 Support scheduling in the fixture's package-private in-memory store and outbox in `saga-forge-test/…/test/` (copy of T012/T024 semantics; G-009-02 noted)
- [X] T040 Add `When.timeElapses(Duration)` / `When.timeReaches(Instant)` (advance `FixtureClock`, deliver every due message in position order until none is due), `Then.expectOpenDeadline`, `expectNoOpenDeadline`, `expectDeadlineExpired`, `expectDeadlineIgnored`, and `InstanceView.openDeadlines()` in `saga-forge-test/…/test/`, per contracts/fixture.md; tests in `saga-forge-test/…test/test/SagaFixtureDeadlineTest.java`
- [X] T041 Extend `FixtureParityTest` in `saga-forge-test/…test/test/` with a deadline scenario compared against a hand-assembled runtime over the in-memory transport; update `saga-forge-test/README.md`

---

## Phase 9: Examples (explicit request)

**Purpose**: a saga with deadlines in the reference examples, per quickstart.md.

- [X] T042 Add `boolean express` to `saga-forge-example/…/example/checkout/events/OrderPlacedEvent.java` and `PAYMENT_TIMED_OUT` to `Order.RejectionReason` in `saga-forge-example/…/example/checkout/Order.java`; update producers of the event
- [X] T043 Add the payment window to `saga-forge-example/…/example/checkout/CheckoutSaga.java` as in quickstart.md: `paymentWindow` written in `onOrderPlaced`, `@StartDeadline(name = "paymentWindow", durationFrom = "paymentWindow", cancelledBy = "onPaymentDeclined")` on `onStockReserved`, `@StartCompensation @DeadlineExpired("paymentWindow") @SagaStep onPaymentWindowExpired(DeadlineReached)`, and `cancelCheckout` mapping `DEADLINE_EXPIRED` to `PAYMENT_TIMED_OUT`; update the class Javadoc's step table
- [X] T044 Add the courier-assignment deadline to `saga-forge-example/…/example/checkout/FulfilmentSaga.java`: `@StartDeadline(name = "courierAssignment", duration = "${checkout.courier-assignment:PT2H}", cancelledBy = "onShipmentDispatched")` on the step that sends `RequestShipmentCommand`, and `@StartCompensation @DeadlineExpired("courierAssignment") @SagaStep onCourierNotAssigned()`; compensation sends what the current undo path sends for a failed shipment; update the class Javadoc's step table
- [X] T045 Fixture tests advancing `FixtureClock` in `saga-forge-example/…test/example/checkout/CheckoutSagaFixtureTest.java` (abandoned challenge ⇒ `COMPENSATED`, `RejectOrderCommand(PAYMENT_TIMED_OUT)`, outcome trigger `DEADLINE_EXPIRED`; express vs standard window; payment in time ⇒ no timeout after an hour; decline cancels the deadline) and `FulfilmentSagaFixtureTest.java` (no courier in 2 h ⇒ compensates; dispatch in time ⇒ still `RUNNING` 3 h later waiting for delivery); extend `CheckoutAndFulfilmentTest.java` with one end-to-end timeout
- [X] T046 Wire the example runtime with an `IN_TRANSACTION` scheduling outbox wherever `saga-forge-example` assembles a runtime by hand (main class or test setup), so the deadlines pass the startup check
- [ ] T047 [P] Keep `saga-forge-example-spring` in step: add a courier-assignment deadline property (`checkout.courier-assignment`) to its `application.yml` if it hosts the checkout sagas, otherwise add a deadline to its canonical saga mirroring `OrderProcessingSaga`'s structure, with a container test that restarts before the due time (SC-001 via Spring)

---

## Phase 10: README and documentation (explicit request)

- [X] T048 Update `README.md`: the checkout story mentions the payment window and courier deadline, with the `@StartDeadline`/`@DeadlineExpired` snippet copied from the compiling `CheckoutSaga`; feature list gains deadlines and scheduled commands
- [X] T049 Update `docs/use-cases.md` §1: payment-window timeout and courier-assignment timeout as worked paths (steps, what compensates, outcome trigger), replacing the "deadlines come with 017" note
- [X] T050 [P] Add `docs/guide/deadlines.md` (declaring, durations, cancellation, timeout steps, scheduled commands, outbox requirement, poll-interval latency, testing with `timeElapses`) with snippets copied from compiling sources and naming them; link it in `mkdocs.yml` and `docs/guide/README.md`; update `model-reference.md`, `testing.md`, `operations.md` (CANCELLED messages, counters) and `limitations.md` (G-017-01..05)
- [X] T051 [P] Update `docs/architecture.md` (feature gaps: deadlines done; message store scheduling) and `docs/agents/saga-forge/SKILL.md` and `docs/agents/AGENTS-snippet.md` with the new annotations and rules; run `docs/guide/build-llms-full.sh` and commit the regenerated `llms-full.txt`
- [X] T052 Update `.specify/memory/memory.md` (close G-ENG-25; add G-017-01..05 from research.md; note G-005-01/-02 still open), `.specify/memory/roadmap.md` 017 status, and the AGENTS.md "Current Status", module map and "Known Gaps" entries for 017

---

## Phase 11: Polish & verification

- [X] T053 Run `mvn -Pcontainer-tests verify` from the repository root; record the date and test count in the AGENTS.md status line
- [X] T054 Run `mvn -P release -Dgpg.skip -DskipTests install` (Javadoc: no `<h3>` in type comments, no `@throws` on record comments) and `mkdocs build --strict`
- [X] T055 Run `/speckit-analyze` against spec, plan and tasks, and resolve any inconsistency it reports

---

## Dependencies & Execution Order

- **Setup (T001–T002)** → **Foundational (T003–T019)** → stories.
- Within Foundational: T003–T009 in parallel where marked; T010 before T011–T016; T011 before T012/T014; T013 before T014/T015; T004 before T017–T019.
- **US1 (T020–T027)** depends on all of Foundational. It is the MVP.
- **US2 (T028–T030)** depends on US1 (T020–T023).
- **US3 (T031–T033)** depends on US1; independent of US2.
- **US4 (T034–T035)** depends on Foundational and T024; independent of US1's runtime delivery path.
- **US5 (T036–T038)** depends on Foundational; T037 also needs T028.
- **Fixture (T039–T041)** depends on US1 (and US4 for scheduled commands).
- **Examples (T042–T047)** depend on US1, US2 and the fixture.
- **Docs (T048–T052)** depend on the examples (snippets are copied from compiling code).
- **Polish (T053–T055)** last.

## Parallel Opportunities

- Foundational: T003, T004, T005, T006, T007, T009 together; T013 and T016 beside T011/T012.
- After US1: US2, US3, US4 and US5 can proceed in parallel (different files, except `SagaRuntime.java` shared by T028 and T036 — do those in sequence).
- Docs: T050 and T051 in parallel after T048/T049.

### Example: US1 in parallel

```text
Developer A: T020 → T021 → T022 → T023 → T025
Developer B: T024 (transport outbox) → T027 (restart container test)
Developer C: T026 (telemetry) after T023
```

## Implementation Strategy

1. **MVP**: Setup + Foundational + US1 → one deadline that times out and compensates, durable across restarts. Demo with `DeadlineExpiryTest` and the restart test.
2. **Increment**: US2 (several deadlines, context/property durations) and US3 (guarantees) — together these make the checkout story expressible.
3. **Increment**: US4 (scheduled commands), US5 (startup rejections), fixture.
4. **Ship**: examples (Phase 9), README and docs (Phase 10), whole-build verification (Phase 11).
