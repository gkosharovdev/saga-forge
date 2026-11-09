# Quickstart: Validating Step Retry and Idempotent Command Processing

**Feature**: `005-step-retry` | Contracts: [retry-policy](./contracts/retry-policy.md),
[command-identity](./contracts/command-identity.md), [attempt-loop](./contracts/attempt-loop.md),
[storage-delta](./contracts/storage-delta.md)

## Prerequisites

- JDK 21, Maven 3.9+.
- Docker only for the Postgres container suite (step 5).
- A clean build (G-REPO-02: run `mvn clean` once if the IDE compiled into `target/`).

## 1. Core: annotation, classification, value types

```bash
mvn -q -pl saga-forge-core test
```

Expect: Property 29 tests reject each invalid `@RetryPolicy` with the documented message and accept valid ones;
`CommandId` is stable for equal components and differs when any component differs; `SagaFailure`,
`StepRecord`, `SagaInstance` and `SagaLifecycleEntry` enforce the rules in [data-model.md](./data-model.md).

## 2. Engine: idempotency (US1, US2)

```bash
mvn -q -pl saga-forge-engine -am test
```

Scenarios covered over the in-memory doubles (`TransactionalInMemoryStorageProvider`, `TestTransport`):

| Scenario | Expected |
|---|---|
| Unit of work fails after `ChargePaymentCommand` handled; event delivered again | handler sees the same `CommandId.value()` both times |
| Two commands of one type in one step | ordinals 1 and 2, distinct values, stable across attempts |
| Same command identity found in committed `handledCommands` | handler not invoked, `saga.command.skipped` logged |
| Event for a `COMPLETED` step, instance running or ended | `DUPLICATE`, nothing written, step not invoked |
| Event redelivered after a rollback | not a duplicate; step runs |

## 3. Engine: retry (US3, US4, US5)

Same command as step 2; tests use a recording `RetryPause` (no real sleeping).

| Scenario | Expected |
|---|---|
| Policy 3 attempts, transient failure twice then success | committed once; `StepRecord.attempts == 3`; pauses 200 ms, 400 ms; two `ATTEMPT_FAILED` entries |
| No policy, any failure | one attempt, `FAILED`, classification by marker or `UNCLASSIFIED` |
| `FinalFailure` on attempt 1 | no retry, `FAILED` `FINAL`, attempts 1 |
| Unmarked failure not in `retryOn` | no retry, `FAILED` `UNCLASSIFIED` |
| Transient on every attempt | `FAILED` `EXHAUSTED`, attempts 3, last cause recorded |
| Transient, transient, final | `FAILED` `FINAL`, attempts 3 |
| Pause interrupted after attempt 2 | `NOT_HANDLED`, interrupt flag set, `pendingRetry.attemptsStarted == 3`; redelivery → `FAILED` `EXHAUSTED` without running |
| Engine restarted after attempt 1 failed (pendingRetry 2), redelivery | attempt 3 runs; never a 4th |
| Policy total backoff 45 s with default maximum | `start()` fails with the total-backoff error in the `StartupReport` |

## 4. Fault-injection idempotency suite (SC-001)

A parameterised engine test fails, retries and redelivers at every step and handler boundary of the example saga
against a fake payment provider that records charges per idempotency key. Expect exactly one charge per order in
every run.

## 5. Postgres

```bash
mvn -q -pl saga-forge-storage-postgres -am test
mvn -q -pl saga-forge-storage-postgres -am test -Pcontainer-tests
```

Expect: the statement-recording fake shows the new columns written and read; with Docker, round-trips of
`pendingRetry`, `attempts`, `handledCommands` and classification; a 004-era row (no new fields) loads with the
defaults; `schema-upgrade-005.sql` applies cleanly to a database created from the 004 `schema.sql`;
`ForwardTransition` rejects a changed `handledCommands` on a completed step. (The container suites have never been
run so far, G-003-23; record the result.)

## 6. Example

```bash
mvn -q -pl saga-forge-example -am test
```

Expect: `processPayment` declares the policy from [contracts/retry-policy.md](./contracts/retry-policy.md);
`PaymentService.handle` takes a `CommandId`; a provider timeout on the first attempt results in one charge and a
completed order.
