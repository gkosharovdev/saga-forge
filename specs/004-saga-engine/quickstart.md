# Quickstart: validating the Saga Engine Runtime

**Feature**: [spec.md](./spec.md) | **Contracts**: [contracts/](./contracts/) | **Data model**:
[data-model.md](./data-model.md) | **Research**: [research.md](./research.md)

Every check here is a unit test over in-memory or test SPI implementations (research §14). No Postgres, Kafka,
Spring or container runtime is needed or started.

## Prerequisites

- JDK 21 and Maven at `/opt/homebrew/bin/mvn`.
- Repository root `saga-forge`, branch `004-saga-engine`.
- Run `clean` first: IDE-compiled classes in `target/` can mask source changes (G-REPO-02).
- `saga-forge-engine` and `saga-forge-example` use `saga-forge-core`'s test-jar (and the example the engine's), so
  module runs use `verify`, which packages the test-jars inside the reactor; `verify` runs no extra tests (no failsafe
  is configured).

## Commands

```bash
# while working on the engine (core + engine)
/opt/homebrew/bin/mvn -B -pl saga-forge-engine -am clean verify

# while working on the Postgres adaptation (core + Postgres unit tests; container classes excluded by tag)
/opt/homebrew/bin/mvn -B -pl saga-forge-storage-postgres -am clean verify

# canonical example on the engine
/opt/homebrew/bin/mvn -B -pl saga-forge-example -am clean verify

# full unit suite, once at the end of each implementation phase
/opt/homebrew/bin/mvn -B clean verify

# container suites (not run in this feature; needs a container runtime, G-003-23)
/opt/homebrew/bin/mvn -B -pl saga-forge-storage-postgres -am clean verify -Pcontainer-tests
```

Each must end in `BUILD SUCCESS` with zero failures. The container classes carry `@Tag("container")` and are excluded
by default (G-003-27 closed), so no `-Dtest` exclusion is needed.

## Validation scenarios

Test classes live in `saga-forge-engine/src/test/java/io/github/gkosharovdev/sagaforge/engine/` unless noted. "Two
engines" means two `SagaEngine` instances sharing only the test stores, modelling two processes.

| # | Scenario | Expected outcome | Spec | Test |
|---|---|---|---|---|
| 1 | `OrderProcessingSaga` (example module): seed the `Order`, `receive(OrderCreatedEvent)` | One instance, `COMPLETED`, outcome `completeOrder`; three steps `COMPLETED` with touched aggregates; payment and order changes committed; report `COMPLETED`, `started = true` | US1-1, US1-5, FR-040 | `saga-forge-example`: `OrderProcessingSagaEngineTest.completesInOneUnitOfWork` |
| 2 | Same, with an observer reading the transactional test store from inside a step | No step record, status or outcome of the running unit of work visible | US1-2, FR-010 | `SagaEngineHappyPathTest$ThreeStep.observerSeesNoPartialState` |
| 3 | `ThreeStepSaga` and `CreateBookingSaga` differential runs: harness vs engine, same clock and ids | Equal status, step records, outcome, failure, aggregate state, context values, dispatched events | SC-001, SC-009 | `ThreeStepSagaDifferentialTest`, `CreateBookingSagaDifferentialTest`, `FailureDifferentialTest` |
| 4 | `ThreeStepSaga` (declared out of order) | Steps run in event order | US1-6 | `SagaEngineHappyPathTest$ThreeStep.stepsRunInEventOrder` |
| 5 | Failure injected at each position of the failure fixture (each step, each handler, loader, saver, resolver, handler-instance provider, parameter resolver, instance write, commit, in-transaction handoff), and a step that catches a handler's exception | Zero aggregate changes, zero `COMPLETED` steps from that unit of work, zero events handed off; instance `FAILED` naming the step; earlier context values readable | US2-1..4, SC-002, FR-011 | `FailurePositionTest.failureAtPosition` (14 positions), `.caughtHandlerFailure` |
| 6 | Event for a `FAILED` / `COMPLETED` instance; non-start event with no instance | `UNEXPECTED` / `UNMATCHED`; no effect, no exception | US2-5, US3-5, FR-004 | `FailurePositionTest.eventForFailedInstance`, `RoutingOutcomesTest.unmatched`, `.unexpectedForMostRecent` |
| 7 | `CreateBookingSaga` first unit of work on engine A; *payment confirmed* / *payment declined* on engine B | `RUNNING` after A; B reaches `onBookingConfirmed` / `onPaymentDeclined` (claim id read from context); untaken steps `NOT_RUN`; same state as an uninterrupted run | US3-1..3, SC-004, FR-014 | `ResumeAcrossEnginesTest` (`resumesOnPaymentConfirmed`, `resumesOnPaymentDeclined`, `interruptedEqualsUninterrupted`) |
| 8 | Second unit of work of `CreateBookingSaga` fails on engine B | First unit of work's effects kept; `FAILED` | US3-4 | `ResumeAcrossEnginesTest.secondUnitFailsOnEngineB` |
| 9 | Two engines, two events for one waiting instance, concurrently, 100 repetitions | Never overlapping (step-entry probe), no lost step record, second sees first's context; never `FAILED` from the race | US4-1..3, SC-003, Property 28 | `ConcurrentUnitsOfWorkTest.twoEnginesSerialize`, `.oneEngineSerializes` (100 repetitions each) |
| 10 | First unit of work completes the saga; second event then handled | `UNEXPECTED` | US4-2 | `ConcurrentUnitsOfWorkTest.secondEventAfterCompletionIsUnexpected` |
| 11 | Storage double injects a `SagaConcurrencyConflictException` at write | Rolled back, no `FAILED` write, `NOT_HANDLED`, no entries recorded | US4-4, FR-026 | `ConcurrencyConflictTest.conflictIsNotHandledAndRedeliverable` |
| 12 | Concurrent events for two instances, one blocked in a step | The other commits without waiting | US4-5, FR-028 | `ConcurrentUnitsOfWorkTest.differentInstancesDoNotWait` |
| 13 | One engine, concurrent start events with the same `orderId`, 100 repetitions | Exactly one `started`; the rest `DUPLICATE_START` | SC-012, Property 25 | `ConcurrentDuplicateStartTest.exactlyOneStarts` (100 repetitions) |
| 14 | Test transport (only `receive` and handoffs) runs `OrderProcessingSaga` and `CreateBookingSaga`, looping dispatched events back from inside the `AFTER_COMMIT` handoff | Both complete; looped events run as new units of work after the first finished; report outcomes as in contracts/event-receiver.md | US5-1, US5-5, SC-007 | `TestTransportEndToEndTest` (`bookingConfirmed`, `paymentDeclined`, `loopedBackEventRunsAsNewUnitOfWork`) |
| 15 | Handoff inspection: rolled-back unit of work; committed one with events left queued at `@EndSaga`; a handoff that throws after commit | Nothing / exactly the buffered events in recording order after commit / instance stays committed, report lists the failure | US5-2..4, SC-005, FR-031 | `HandoffObligationsTest` |
| 16 | `CapturingEventHandoff` with `IN_TRANSACTION` timing | Per committed unit of work: its events, recording order, `unitOfWorkId` (+ saga type and id) before commit; nothing retained for rolled-back ones; failure inside it → `FAILED` | US5-7, SC-011, Property 26 | `InTransactionHandoffTest`, `HandoffObligationsPropertyTest` |
| 17 | Lifecycle history of scenarios 1, 5 and 7, with step names that sort differently from execution order | `CREATED`, `STEP_COMPLETED` in execution order, `COMPLETED`/`FAILED`; none from rolled-back units of work | US1-8, SC-010, Property 27 | `LifecycleHistoryTest`, `LifecycleHistoryPropertyTest`; scenario 1: `OrderProcessingSagaEngineTest.completesInOneUnitOfWork`; 5: `FailurePositionTest.failureAtPosition`; 7: `ResumeAcrossEnginesTest.resumesOnPaymentConfirmed` |
| 18 | `start()` with each startup error alone and all together (invalid `validateAll` model, aggregate without loader/resolver, unresolvable parameter, saga without no-arg constructor, dispatcher rejecting a saga type) | One `SagaEngineStartupException` listing every error; `receive` then throws `IllegalStateException`; `registerSagaType` called for every saga type before the first event on success | US6-1..5, SC-006, Properties 21–24, FR-036 | `StartupValidationTest`, `StartupChecksPropertyTest` |
| 19 | `receive` from inside a step, a handler and an `IN_TRANSACTION` handoff | `IllegalStateException`; that unit of work `FAILED` | FR-007 | `ReentrancyTest` |
| 20 | `receive(null)`, a non-`@DomainEvent`, an event missing its association value | `IllegalArgumentException`; nothing ran | FR-006 | `RoutingOutcomesTest.invalidEventsRejected` |
| 21 | `EventPublisher.publish` outside a unit of work; `CommandGateway.send` outside one (commit and failure) | Immediate `dispatch`; non-saga unit of work commits handler changes and hands off its events with `sagaType`/`sagaId` `null`, or rolls back and rethrows | US1-7, FR-016, FR-018 | `OutsideUnitOfWorkTest`; `OrderProcessingSagaEngineTest.publishedEventStartsTheSaga` |
| 22 | Recording `System.Logger` across scenarios 5, 6, 11, 15, 18 | Each diagnostic of contracts/diagnostics.md appears with event type, saga type and saga id where known | FR-038 | `DiagnosticsTest` |
| 23 | Postgres module unit tests (`saga-forge-storage-postgres/src/test/.../postgres/`): JDBC fake, joined and not joined; `JdbcTransactionBoundary`; `LifecycleEntryRows`; `ForwardTransition` | As research §15 | FR-023 | `saga-forge-storage-postgres`: `JdbcTransactionBoundaryTest`, `PostgresStorageProviderJoinTest`, `PostgresStorageProviderLockTest`, `LifecycleEntryRowsTest`, `ForwardTransitionPropertyTest` |
| 24 | Dependency trees before/after: `/opt/homebrew/bin/mvn -B dependency:tree` at the root on `main` and on this branch | No new third-party artifact in any module; only `saga-forge-engine` and the internal test-jars appear | SC-008, FR-037 | Manual (tasks.md T109): POM diff against `master` and per-module third-party `dependency:tree` comparison |

## Not verified here

Everything that needs a real Postgres: statement validity, `FOR NO KEY UPDATE` blocking and its compatibility with
the context store's foreign-key check, cross-process serialization on a real database, commit/rollback through
`JdbcTransactionBoundary` (research §15, G-003-23, G-ENG-11).

## Container tests for the user to run

The implementer did not run these (tasks.md rule 3). Closing G-003-23, G-003-22, G-003-21 and G-ENG-11 needs this run
to pass. They need a Docker-compatible container runtime; Testcontainers starts `postgres:16-alpine`.

| Class | Methods | Verifies | Covers |
|---|---|---|---|
| `SchemaTest` | all (`schemaApplies`, `validRowsAccepted`, `completedRequiresOutcome`, `failedRequiresFailureStep`, `deleteCascades`) | the DDL and its constraints; only the tag changed in 004, the schema did not | G-003-23 |
| `PostgresStorageProviderTest$RoundTrip` | all (`runningInstanceSurvivesRestart`, `unknownIdIsEmpty`, `terminalInstancesRoundTrip`, `resaveReplaces`, `findByAssociation`, `delete`, `nullRules`, `nullInstanceFieldsRejected`); `nullRules` extended for `save(instance, entries)` | the own-transaction write and read paths under the new write contract | FR-023, G-003-23 |
| `PostgresStorageProviderTest$Atomicity` | `failedHistoryInsertKeepsPreCallSnapshot`, `failedFirstSaveLeavesNothing` (now with handed entries) | a failing history insert rolls the whole save back | FR-023, G-003-23 |
| `PostgresSagaContextStoreTest` | all (`contextSurvivesInstanceRollback`, `resaveKeepsLatest`, `missingRecordIsEmpty`, `structuredAndScalarValuesRoundTrip`, `deleteRemovesRecordOnly`, `providerDeleteCascadesToContext`, `nullsAreRejected`, `saveWithoutInstanceFails`); tag only | the context store's own transaction and the instance-first foreign key | FR-022, G-ENG-01 |
| `LifecycleHistoryTest` | `fullLifecycleInOrder`, `notRunStepHasNoRow`, `failedRowMatchesInstanceFailure`, `saveWithoutEntriesAddsNoRow`, `oneSaveKeepsHandOverOrder`, `entriesAppendAfterExistingHistory`, `deleteRemovesHistory` | history rows are exactly the handed entries, numbered in hand-over order | FR-033, SC-010, G-003-04, G-003-16, G-ENG-02 |
| `ConcurrencyControlTest` (existing) | `staleSequentialWriteRejected`, `divergingConcurrentSaves`, `identicalConcurrentSaves`, `terminalCompletedStatusIsFinal`, `terminalFailedStatusIsFinal`, `completedStepFactsAreFinal`, `concurrentFirstSaves` | the forward-transition check and the first-save race under `FOR NO KEY UPDATE` | G-003-22, G-003-17, G-003-23, G-ENG-22 |
| `ConcurrencyControlTest` (new in 004) | `findByIdAndLockBlocksSecondTransactionUntilCommit`, `findByIdAndLockAfterWaitReadsWinnersCommit`, `findByIdAndLockDoesNotBlockContextStoreWrite`, `historyNumbersContinueUnderLock` | the row lock serializes two transactions, the waiter reads the winner's commit, the context store's FK check is not blocked, numbering continues under the lock | FR-025, FR-033, G-ENG-11, G-003-21 |
| `JoinedTransactionContainerTest` | `joinedSaveCommitsWithClientWrite`, `joinedSaveRollsBackWithClientWrite`, `contextStoreWriteSurvivesJoinedRollback`, `withoutTransactionKeepsOwnTransaction` | the provider joins a `JdbcTransactionBoundary` transaction with a client write, and keeps its own transaction without one | FR-022, FR-023, G-ENG-03, G-003-23 |

Commands:

```bash
# all Postgres tests, unit and container
/opt/homebrew/bin/mvn -B -pl saga-forge-storage-postgres -am clean verify -Pcontainer-tests
```

```bash
# only the container classes
/opt/homebrew/bin/mvn -B -pl saga-forge-storage-postgres -am clean verify -Pcontainer-tests -Dsurefire.failIfNoSpecifiedTests=false -Dtest='SchemaTest,PostgresStorageProviderTest*,PostgresSagaContextStoreTest,LifecycleHistoryTest,ConcurrencyControlTest,JoinedTransactionContainerTest'
```

With Podman on macOS (from `specs/003-postgres-saga-persistence/quickstart.md`): export
`DOCKER_HOST=unix://<socket>`, with the socket path from
`podman machine inspect --format '{{.ConnectionInfo.PodmanSocket.Path}}'`, and set
`TESTCONTAINERS_RYUK_DISABLED=true` if Ryuk fails under rootless Podman.
