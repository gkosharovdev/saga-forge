# Data Model: Outbox Command Failure Feedback

**Feature**: [spec.md](spec.md) | **Research**: [research.md](research.md)

All changes are additive to released types (DEC-REL-01) unless marked *internal*.

## `core.spi.CommandFailure` (new, value record)

The report a transport hands to the runtime when a saga-scoped outbox command failed for good.

| Component | Type | Rule |
|-----------|------|------|
| `commandId` | `CommandId` | non-null; `sagaScoped()` must be `true` |
| `classification` | `FailureClassification` | `FINAL` or `EXHAUSTED` only |
| `causeType` | `String` | non-blank; fully qualified class name of the last failure |
| `attempts` | `int` | `>= 1`; handler attempts made, including the last |

No failure message is carried (FR-006). `equals` by value; two reports of the same failed delivery are equal, which is
what makes the reaction idempotent to compare in tests.

Derived: `sagaId()` = `commandId.sagaId()`; `senderName()` = `commandId.stepName()`; `commandType()` =
`commandId.commandType()`.

*Implementation note (2026-11-24):* the plan had `commandType` as a component. `CommandId` already carries the
command's fully qualified class name, so a separate component could only disagree with it; the record derives it.

## `core.failure.FailureClassification` (changed)

Adds `static FailureClassification of(Throwable failure)`:
- walks `failure`, then its causes, stopping at a cause already seen;
- the first `FinalFailure` → `FINAL`; else the first `TransientFailure` → `TRANSIENT`;
- otherwise `UNCLASSIFIED`. Never returns `EXHAUSTED` (that is decided by counting attempts).
- `null` → `IllegalArgumentException`.

## `core.compensation.CompensationTriggerKind` (changed)

Adds `COMMAND_FAILED`: an outbox command the saga sent failed `FINAL` or `EXHAUSTED`.

## `core.compensation.CompensationTrigger` (changed)

New canonical form `(kind, stepName, eventType, commandType, failure, at)`. The existing 5-argument constructor stays
and passes `commandType = null`.

Invariants (existing ones unchanged, plus):
- `commandType` is non-null if and only if `kind == COMMAND_FAILED`; non-blank when present.
- For `COMMAND_FAILED`: `eventType == null`; `failure` non-null, classified `FINAL` or `EXHAUSTED`;
  `failure.stepName().equals(stepName)`.
- `stepName` is the step that sent the command.

Factory: `commandFailed(CommandFailure report, Instant at)` takes `stepName` from `report.commandId().stepName()` and
builds `SagaFailure(stepName, report.causeType(), null, at, report.classification(), report.attempts())`.

Telemetry value of the kind: `command_failed`.

## `core.outcome.OutcomeTrigger` (changed)

Gains nullable `commandType`, copied by `OutcomeTrigger.of(CompensationTrigger)`; the existing constructor stays and
passes `null`. `SagaCompensated` and `SagaFailed(COMPENSATION)` that follow a command failure carry
`kind = COMMAND_FAILED`, the sending step and the command type (spec US3).

## `core.spi.CommandReceiver` (changed)

```text
void receiveFailure(CommandFailure failure, Consumer<LocalTransaction> inTransaction)
```

Default method throws `UnsupportedOperationException` naming the receiver class, so an existing third-party receiver
compiles; the runtime's receiver implements it. Contract: [contracts/command-receiver-failure.md](contracts/command-receiver-failure.md).

## `core.spi.store.MessageStore` (changed)

```text
void setAside(LocalTransaction tx, String consumerId, String messageId, DeliveryFailure failure, String reason)
```

Same effect as the existing `setAside`, inside the caller's transaction, like `markConsumed(tx, ...)`. Default method
throws `UnsupportedOperationException`; `InMemoryMessageStore` and `PostgresMessageStore` implement it, and
`MessageStoreContract` covers commit and rollback.

Set-aside reasons (string values, transport-owned): existing `MAX_ATTEMPTS`, `UNREADABLE`; new `FINAL_FAILURE`.

## Saga instance state transitions

Step-sent command failure, instance `CREATED`/`RUNNING` with at least one completed step:

```text
RUNNING --(CommandFailure, StartCompensation, trigger COMMAND_FAILED)--> COMPENSATING
COMPENSATING --(CompensationRun, existing)--> COMPENSATED | COMPENSATION_FAILED
```

The sending step is compensated too (only a `FAILURE_OUTCOME` trigger leaves out its own step). An instance with no
completed step cannot receive a report, because the sending step committed with its command; `NOTHING_COMMITTED` is
treated as an ignored report.

Compensation-sent command failure:

```text
COMPENSATING --(RecordCompensationCommandFailure)--> COMPENSATION_FAILED
   step[forStep].compensation: COMPENSATED --> FAILED (failure recorded)
COMPENSATED  --(report)--> COMPENSATED   (skipped, diagnostic; G-012-01)
```

Ignored with a diagnostic (message only set aside): instance missing, `COMPLETED`, `FAILED`, `COMPENSATED` (step-sent),
`COMPENSATION_FAILED`, `COMPENSATING` (step-sent: compensation is already running; FR-012).

## Runtime-internal types (*internal*)

| Type | Kind | Responsibility |
|------|------|----------------|
| `CommandFailureHandler` | final class | lease the instance, resolve the sender, run the work, run `CompensationRun` if started, release, after-release outbox |
| `StartCompensation` | `Work<CompensationTrigger, NotStarted>` | now built with its trigger; `NotStarted` + `ALREADY_COMPENSATING` |
| `RecordCompensationCommandFailure` | `Work<Void, Ignored>` | admit only `COMPENSATING`; record the compensation's failure and end `COMPENSATION_FAILED` |
| `EngineSagaLifecycle.recordCompensationCommandFailed` | method | the one new transition above |
| `Sender` | sealed: `StepSender(step)`, `CompensationSender(forStep)`, `Unknown` | the sender resolved from the saga model by `CommandId.stepName` |

## Storage

- Postgres `ForwardTransition`: allows a step's compensation `COMPENSATED → FAILED` only when the same save moves the
  instance `COMPENSATING → COMPENSATION_FAILED`.
- `JsonCodec`: writes `commandType` on the trigger only when non-null; reads a missing field as `null`.
- The runtime's `TransactionalInMemoryStorageProvider` and the fixture's storage apply the same transition rule.
