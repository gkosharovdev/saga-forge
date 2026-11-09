# Quickstart: Validating Saga Compensation

**Feature**: `006-saga-compensation` | Contracts: [declaring-compensation](./contracts/declaring-compensation.md),
[compensation-run](./contracts/compensation-run.md), [storage-delta](./contracts/storage-delta.md)

## Prerequisites

- **Toolchain**: Java 21 and Maven, from the repository root.
- **Branch**: `006-saga-compensation`, which builds on `005-step-retry`.
- **Containers**: Docker, for the Postgres container suites (`-Pcontainer-tests`).

```bash
mvn -B clean verify
```

This must pass with every module's existing tests unchanged, apart from the projections noted in tasks.md.

## 1. Core: annotation, rules, value types

```bash
mvn -B -pl saga-forge-core test -Dtest='StartCompensationValidationTest,CompensationValidationTest,CompensationVocabularyTest'
```

Expected:
- **R20 to R22**: each violation produces exactly the error text in contracts/declaring-compensation.md, and valid
  sagas produce none (Properties 30 to 32).
- **Vocabulary**: `CompensationTrigger`, `StepCompensation`, `StepRecord` and `SagaInstance` reject every combination
  the data-model.md rules forbid.

## 2. Engine: business failure and unwinding (US1, US2)

```bash
mvn -B -pl saga-forge-engine -am test -Dtest='BusinessFailureCompensationTest,CompensationOrderTest'
```

Expected:
- **US1**:
  - A `DeliveryRejectedEvent` after the payment committed dispatches exactly one refund and ends `COMPENSATED`, with
    trigger `FAILURE_OUTCOME` / `rejectDelivery` / `DeliveryRejectedEvent`.
  - `StockUnavailableEvent` records its own outcome.
  - `DeliveryAcceptedEvent` completes the saga normally.
  - A second rejection is `DUPLICATE`, and no second refund is dispatched.
- **US2**:
  - Steps A, B, C are compensated in the order C, B, A.
  - A step without a compensation is `NOTHING_TO_COMPENSATE`.
  - A rolled-back step D is never compensated.

## 3. Engine: technical failure, intervention, resume (US3, US4, US5)

```bash
mvn -B -pl saga-forge-engine -am test -Dtest='FinalFailureCompensationTest,CompensationFailureTest,CompensationResumeTest'
```

Expected:
- **US3**:
  - A `FinalFailure` after a committed payment compensates it, with trigger `FINAL_FAILURE`.
  - An unclassified failure ends `FAILED` with no compensation.
  - Exhausted retries compensate with trigger `RETRIES_EXHAUSTED`, carrying the attempts and the last cause.
- **US4**:
  - C is compensated, B fails and A stays `NOT_ATTEMPTED`.
  - The instance ends `REQUIRES_INTERVENTION`, with B's failure recorded and nothing retried.
- **US5**: the engine is stopped after C's compensation committed. The next event resumes with B and A only, and C is
  compensated exactly once.

## 4. Fault injection (SC-002, SC-003)

```bash
mvn -B -pl saga-forge-engine -am test -Dtest='CompensationFaultInjectionTest'
```

Expected:
- **Failure points**: a failure is injected at every step boundary and every compensation boundary of a three-step
  saga. No rolled-back step is compensated, and every committed step with a compensation is compensated exactly once.
- **Crashes**: a crash at every point followed by a resume produces zero duplicate compensations and zero skipped ones
  (Property 34).

## 5. Postgres

```bash
mvn -B -pl saga-forge-storage-postgres -am test -Dtest='CompensationPersistenceTest'
```

```bash
mvn -B -pl saga-forge-storage-postgres -am clean verify -Pcontainer-tests
```

Expected:
- **Round trip**: the trigger, `sequence` and per-step compensation survive a restart.
- **Constraints**: `trigger_iff_compensation` rejects inconsistent rows.
- **Forward transitions**: `ForwardTransition` rejects backwards compensation moves and trigger changes.
- **Upgrade**: `schema-upgrade-006.sql` applied to a 005 database backfills `sequence` from history, and applying it
  twice changes nothing.

## 6. Example

```bash
mvn -B -pl saga-forge-example -am test
```

Expected: `OrderProcessingSaga` refunds once when delivery is rejected after payment (SC-001), and its happy path is
unchanged.
