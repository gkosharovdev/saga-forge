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

Each must end in `BUILD SUCCESS` with zero failures. With the `container` tag in place (research §14) the 003
`-Dtest='!SchemaTest,...'` exclusion is no longer needed; until that task lands, append it to the Postgres and full
runs (G-003-27).

## Validation scenarios

Test classes live in `saga-forge-engine/src/test/java/io/github/gkosharovdev/sagaforge/engine/` unless noted. "Two
engines" means two `SagaEngine` instances sharing only the test stores, modelling two processes.

| # | Scenario | Expected outcome | Spec |
|---|---|---|---|
| 1 | `OrderProcessingSaga` (example module): seed the `Order`, `receive(OrderCreatedEvent)` | One instance, `COMPLETED`, outcome `completeOrder`; three steps `COMPLETED` with touched aggregates; payment and order changes committed; report `COMPLETED`, `started = true` | US1-1, US1-5, FR-040 |
| 2 | Same, with an observer reading the transactional test store from inside a step | No step record, status or outcome of the running unit of work visible | US1-2, FR-010 |
| 3 | `ThreeStepSaga` and `CreateBookingSaga` differential runs: harness vs engine, same clock and ids | Equal status, step records, outcome, failure, aggregate state, context values, dispatched events | SC-001, SC-009 |
| 4 | `ThreeStepSaga` (declared out of order) | Steps run in event order | US1-6 |
| 5 | Failure injected at each position of the failure fixture (each step, each handler, loader, saver, resolver, handler-instance provider, parameter resolver, instance write, commit, in-transaction handoff), and a step that catches a handler's exception | Zero aggregate changes, zero `COMPLETED` steps from that unit of work, zero events handed off; instance `FAILED` naming the step; earlier context values readable | US2-1..4, SC-002, FR-011 |
| 6 | Event for a `FAILED` / `COMPLETED` instance; non-start event with no instance | `UNEXPECTED` / `UNMATCHED`; no effect, no exception | US2-5, US3-5, FR-004 |
| 7 | `CreateBookingSaga` first unit of work on engine A; *payment confirmed* / *payment declined* on engine B | `RUNNING` after A; B reaches `onBookingConfirmed` / `onPaymentDeclined` (claim id read from context); untaken steps `NOT_RUN`; same state as an uninterrupted run | US3-1..3, SC-004, FR-014 |
| 8 | Second unit of work of `CreateBookingSaga` fails on engine B | First unit of work's effects kept; `FAILED` | US3-4 |
| 9 | Two engines, two events for one waiting instance, concurrently, 100 repetitions | Never overlapping (step-entry probe), no lost step record, second sees first's context; never `FAILED` from the race | US4-1..3, SC-003, Property 28 |
| 10 | First unit of work completes the saga; second event then handled | `UNEXPECTED` | US4-2 |
| 11 | Storage double injects a `SagaConcurrencyConflictException` at write | Rolled back, no `FAILED` write, `NOT_HANDLED`, no entries recorded | US4-4, FR-026 |
| 12 | Concurrent events for two instances, one blocked in a step | The other commits without waiting | US4-5, FR-028 |
| 13 | One engine, concurrent start events with the same `orderId`, 100 repetitions | Exactly one `started`; the rest `DUPLICATE_START` | SC-012, Property 25 |
| 14 | Test transport (only `receive` and handoffs) runs `OrderProcessingSaga` and `CreateBookingSaga`, looping dispatched events back from inside the `AFTER_COMMIT` handoff | Both complete; looped events run as new units of work after the first finished; report outcomes as in contracts/event-receiver.md | US5-1, US5-5, SC-007 |
| 15 | Handoff inspection: rolled-back unit of work; committed one with events left queued at `@EndSaga`; a handoff that throws after commit | Nothing / exactly the buffered events in recording order after commit / instance stays committed, report lists the failure | US5-2..4, SC-005, FR-031 |
| 16 | `CapturingEventHandoff` with `IN_TRANSACTION` timing | Per committed unit of work: its events, recording order, `unitOfWorkId` (+ saga type and id) before commit; nothing retained for rolled-back ones; failure inside it → `FAILED` | US5-7, SC-011, Property 26 |
| 17 | Lifecycle history of scenarios 1, 5 and 7, with step names that sort differently from execution order | `CREATED`, `STEP_COMPLETED` in execution order, `COMPLETED`/`FAILED`; none from rolled-back units of work | US1-8, SC-010, Property 27 |
| 18 | `start()` with each startup error alone and all together (invalid `validateAll` model, aggregate without loader/resolver, unresolvable parameter, saga without no-arg constructor, dispatcher rejecting a saga type) | One `SagaEngineStartupException` listing every error; `receive` then throws `IllegalStateException`; `registerSagaType` called for every saga type before the first event on success | US6-1..5, SC-006, Properties 21–24, FR-036 |
| 19 | `receive` from inside a step, a handler and an `IN_TRANSACTION` handoff | `IllegalStateException`; that unit of work `FAILED` | FR-007 |
| 20 | `receive(null)`, a non-`@DomainEvent`, an event missing its association value | `IllegalArgumentException`; nothing ran | FR-006 |
| 21 | `EventPublisher.publish` outside a unit of work; `CommandGateway.send` outside one (commit and failure) | Immediate `dispatch`; non-saga unit of work commits handler changes and hands off its events with `sagaType`/`sagaId` `null`, or rolls back and rethrows | US1-7, FR-016, FR-018 |
| 22 | Recording `System.Logger` across scenarios 5, 6, 11, 15, 18 | Each diagnostic of contracts/diagnostics.md appears with event type, saga type and saga id where known | FR-038 |
| 23 | Postgres module unit tests (`saga-forge-storage-postgres/src/test/.../postgres/`): JDBC fake, joined and not joined; `JdbcTransactionBoundary`; `LifecycleEntryRows`; `ForwardTransition` | As research §15 | FR-023 |
| 24 | Dependency trees before/after: `/opt/homebrew/bin/mvn -B dependency:tree` at the root on `main` and on this branch | No new third-party artifact in any module; only `saga-forge-engine` and the internal test-jars appear | SC-008, FR-037 |

## Not verified here

Everything that needs a real Postgres: statement validity, `FOR NO KEY UPDATE` blocking and its compatibility with
the context store's foreign-key check, cross-process serialization on a real database, commit/rollback through
`JdbcTransactionBoundary` (research §15, G-003-23, G-ENG-11).
