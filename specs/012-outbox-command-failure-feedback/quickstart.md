# Quickstart: validating outbox command failure feedback

**Feature**: [spec.md](spec.md) | **Contracts**: [contracts/](contracts/)

## Prerequisites

- JDK 21, Maven 3.9+.
- Docker, only for the container suites (`-Pcontainer-tests`).

## 1. Unit and fixture suites

```bash
mvn -q -pl saga-forge-core,saga-forge-runtime,saga-forge-transport-inmemory,saga-forge-test,saga-forge-example -am test
```

Expected: green. The new cases to look for:

| Suite | Proves |
|-------|--------|
| `FailureClassificationOfTest` (core) | `of(Throwable)`: markers through the cause chain, cycles, `Error` |
| `CommandFailureTest`, `CompensationTriggerTest` (core) | value rules; `COMMAND_FAILED` invariants; old constructors still work |
| `CommandFailureCompensationTest`, `IgnoredCommandFailureTest`, `CompensationCommandFailureTest`, `SenderTest`, `CommandFailureTelemetryTest`, `CompensationFaultInjectionTest` (runtime) | [runtime-reaction.md](contracts/runtime-reaction.md) §A, §B, diagnostics, idempotence (FR-013), crash safety (SC-003) |
| `UnitOfWorkBoundaryTest` (runtime) | every write still in `UnitOfWork` (FR-007) |
| `CommandFailureFeedbackTest`, `CommandDeliveryTest`, `TransportTelemetryTest`, `TransportAcceptanceSuite` (transport) | [relay-classification.md](contracts/relay-classification.md) table, SC-003, SC-004 |
| `MessageStoreContract` (transport, in-memory store) | `setAside(tx, ...)` commits and rolls back with the transaction |
| `OutboxCommandFailureTest`, `OutboxCommandFailureParityTest` (test) | [fixture.md](contracts/fixture.md) |
| `checkout/FulfilmentSagaFixtureTest` (example) | `FulfilmentSaga` compensates when `CapturePaymentCommand` fails for good (the canonical `OrderProcessingSaga` sends no outbox command) |

## 2. Scenario walk-through in a fixture test

In `saga-forge-example`, the scenario the new example test covers:

1. Given an order saga whose start step committed and whose payment step sent an outbox command.
2. When the command's handler throws an exception marked `FinalFailure`.
3. Then, in the same `when` step:
   - the handler ran once;
   - the instance is `COMPENSATED`, the payment and start steps' compensations ran, newest first;
   - `EventResult.outcomeEvents()` holds one `SagaCompensated` whose `trigger().kind()` is `COMMAND_FAILED`, naming
     the command type and the sending step;
   - the instance's compensation trigger records the classification `FINAL`, the cause type and `attempts = 1`, and no
     failure message.

Repeat with an unmarked exception and `outboxCommandAttempts(3)`: three attempts, then the same reaction with
`EXHAUSTED`.

## 3. Container suites (Postgres)

```bash
mvn -q -Pcontainer-tests -pl saga-forge-storage-postgres,saga-forge-transport-inmemory,saga-forge-spring-boot-starter -am verify
```

Expected: green, including:
- `MessageStoreContract` against `PostgresMessageStore` (`setAside(tx, ...)`);
- the trigger's `commandType` round-trips through `JsonCodec`, and a row written before 012 reads with `null`;
- `ForwardTransition` accepts `COMPENSATED → FAILED` only together with `COMPENSATING → COMPENSATION_FAILED`;
- the transport acceptance suite's crash case over Postgres: kill between handler failure and set-aside, restart,
  exactly one reaction (SC-003).

## 4. Release build

```bash
mvn -P release -Dgpg.skip -DskipTests install
```

Expected: green (Javadoc of the new public types has no `<h3>` and no `@throws` on record comments).

## 5. Docs

```bash
docs/guide/build-llms-full.sh && git diff --exit-code docs/guide/llms-full.txt
```

Expected: no diff after the guide's failure-handling page and `docs/agents/` are updated.
