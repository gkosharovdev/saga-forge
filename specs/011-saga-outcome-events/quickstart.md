# Quickstart: Validating the Unit-of-Work Refactoring, the Rename and Outcome Events

**Feature**: `011-saga-outcome-events` | Contracts: [outcome-events.md](contracts/outcome-events.md),
[unit-of-work.md](contracts/unit-of-work.md),
[builders-and-properties.md](contracts/builders-and-properties.md)

## Prerequisites

- JDK 21, Maven, Docker (for the container suites only).
- The branch builds: `mvn -q install -DskipTests`.

## Commands

Unit suites (runtime, transport, fixture, starter without containers):

```bash
mvn test
```

Container suites (Postgres message store round-trip, Spring and Modulith acceptance):

```bash
mvn verify -Pcontainer-tests
```

Release build (Javadoc of the new public types):

```bash
mvn -P release -Dgpg.skip -DskipTests install
```

## Phase gate: the refactoring changes nothing

Before any rename or outcome-event change, on the refactoring commit alone:

| # | Check | Expected |
|---|---|---|
| G1 | `mvn verify -Pcontainer-tests` | every existing suite passes unchanged (same tests, same assertions) |
| G2 | `FixtureParityTest`, the 002 harness comparison tests | pass unchanged |
| G3 | `UnitOfWorkBoundaryTest` (ArchUnit, new) | only `UnitOfWork` calls `begin`, `findByIdAndLock`, `save`, `store`, `committed` (rule U1) |
| G4 | `rg -l "class (Saga|Compensation|NonSaga)UnitOfWork|class (Failed|Attempt|CompensationStart|CompensationFailed)Write" saga-forge-runtime` | no match |

Baseline before any 011 source change (`mvn verify -Pcontainer-tests` on `bfaa25f`, 2026-11-20): 1,612 tests, 1 skipped
(`TelemetryOverheadTest`). Per module: core 467, runtime 706, test 56, transport-inmemory 58, storage-postgres 217,
starter 86, example 16, example-spring 6. G1 expects these counts plus `UnitOfWorkBoundaryTest`.

## Scenarios

Each scenario names the user story it proves and the test that should hold it. Test classes are proposals for
`/speckit-tasks`.

| # | Scenario | Expected | Story / FR | Where |
|---|---|---|---|---|
| Q1 | Start step throws `FinalFailure` | instance `FAILED`; one `SagaFailed(STEP, FINAL)` after commit; `eventId = <sagaId>:FAILED` | US1, FR-004, FR-009 | runtime `OutcomeEventsTest`; fixture test in `saga-forge-example` |
| Q2 | Retries exhausted before any commit | one `SagaFailed(STEP, EXHAUSTED)` with the attempt count | US1 | runtime `OutcomeEventsTest` |
| Q3 | `FAILED` write finds the instance terminal | no outcome event | US1 sc. 4, FR-010 | runtime `OutcomeEventsTest` |
| Q4 | Canonical saga completes | one `SagaCompleted("completeOrder")`, last in the batch | US2, FR-003, FR-012 | `OrderProcessingSagaFixtureTest` |
| Q5 | `rejectOrder` (`@StartCompensation`) after payment committed | one `SagaCompensated` with trigger `rejectOrder` | US2, FR-005 | `OrderProcessingSagaFixtureTest` |
| Q6 | A compensation throws | instance `COMPENSATION_FAILED`; outcome `COMPENSATION_FAILED`; one `SagaFailed(COMPENSATION)` naming the compensated step and carrying the trigger | US2 sc. 3, US4, FR-006, FR-019 | runtime `OutcomeEventsTest`, `CompensationFailureTest` |
| Q7 | Further event for an ended instance | no second outcome event | US2 sc. 4 | runtime `OutcomeEventsTest` |
| Q8 | In-memory transport over Postgres; stop after terminal commit, before delivery; restart | outcome event delivered once after restart | US3, SC-003 | transport container suite |
| Q9 | Terminal write rolls back (fault injected in the `IN_TRANSACTION` outbox) | no outcome event stored; for `RecordFailure`, `failedNotRecorded` | US3 sc. 1, SC-004 | runtime fault-injection test |
| Q10 | Spring example, saga fails | a `@TransactionalEventListener(SagaFailed)` and an `@ApplicationModuleListener` each receive it after commit | US3 sc. 3, FR-015 | starter / Spring example container suite |
| Q11 | Outcome event delivered to `EventReceiver` | empty report, no exception, message consumed | FR-017 | runtime `EventRouterTest`; transport acceptance |
| Q12 | `@SagaStep` taking `SagaFailed` | validation error "not a `@DomainEvent`" | FR-017 | core scanner test |
| Q13 | History of a failed compensation | one `COMPENSATION_FAILED` entry as the last entry; no other kind after it | FR-020 | runtime and Postgres `LifecycleHistoryTest` |
| Q14 | 0.1.0 database with a `REQUIRES_INTERVENTION` instance and history row; apply `schema-upgrade-011.sql` twice | instance reads `COMPENSATION_FAILED` with its fields intact; old history row gone; second run changes nothing | US4 sc. 2, FR-021 | Postgres container `SchemaUpgrade011Test` |
| Q15 | Search the code and docs for "intervention" | only historical specs (001 to 010), memory.md and this feature's spec mention it | SC-005, FR-022 | `rg -i intervention --glob '!specs/0[01][0-9]-*/**' --glob '!.specify/**'` |
| Q16 | `outcomeEvents(false)` | batches equal to those before outcome events | FR-016, SC-006 | runtime `OutcomeEventsTest` |
| Q17 | Existing suites | pass unchanged except added outcome-event assertions and the renamed status | SC-006 | `mvn verify -Pcontainer-tests` |
