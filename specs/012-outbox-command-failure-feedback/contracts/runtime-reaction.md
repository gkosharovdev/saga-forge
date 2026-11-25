# Contract: Runtime reaction to a command failure

**Module**: `saga-forge-runtime` (`internal.CommandFailureHandler` and works)

## Sender resolution

`CommandId.stepName()` against the instance's saga model (type read from the locked instance):

| Name is | Sender | Reaction |
|---------|--------|----------|
| a `@SagaStep` method | step | §A |
| a `@SagaCompensation` method | compensation (mapped to its `forStep`) | §B |
| neither | unknown | ignore, diagnostic `unknown-sender` |

## §A Step-sent command

1. `StartCompensation(CompensationTrigger.commandFailed(report, now))` in `UnitOfWork.forInstance(sagaId)`; the work
   calls `inTransaction`.
   - admission `MISSING_OR_TERMINAL` / `ALREADY_COMPENSATING` → skipped; the handler runs `inTransaction` in a standalone
     `HandleCommand` and logs the diagnostic (FR-012).
   - admission `NOTHING_COMMITTED` cannot occur (the sending step committed with its command); if it does, it is
     ignored like a missing instance, diagnostic `nothing-committed`.
2. On commit: `CompensationRun` compensates every completed step, the sending step included, newest first, each in
   its own unit of work (006). Only a `FAILURE_OUTCOME` trigger leaves out its own step
   (`EngineSagaLifecycle.startCompensation`); `COMMAND_FAILED` does not, because the sending step's own work
   committed (spec US1 scenario 1). With no compensation declared on any completed step the instance ends
   `COMPENSATED` at once (spec edge case).
3. Ends with exactly one outcome event: `SagaCompensated` or `SagaFailed(COMPENSATION)`, both carrying
   `OutcomeTrigger(kind = COMMAND_FAILED, stepName, commandType)` (SC-002).

## §B Compensation-sent command

`RecordCompensationCommandFailure(forStep, report)` in `UnitOfWork.forInstance(sagaId)`; calls `inTransaction`.
- `COMPENSATING` → step `forStep`'s compensation `COMPENSATED → FAILED` with the failure, instance
  `COMPENSATION_FAILED`, `SagaFailed(COMPENSATION)`; compensations not yet run do not run (FR-012a).
- `COMPENSATED` → skipped, diagnostic `already-compensated` (G-012-01).
- otherwise → skipped, diagnostic.

## Diagnostics

One `System.Logger` `WARNING` per ignored report: saga id, command type, command id, reason
(`missing`, `terminal:<status>`, `compensating`, `already-compensated`, `unknown-sender`, `nothing-committed`). No failure message.

## Telemetry

`saga_forge.handle` span with `saga_forge.compensation.trigger = COMMAND_FAILED` (the existing key, with the enum name
as for every trigger kind), `saga_forge.command.type`, `saga_forge.failure.classification` and
`saga_forge.handling.outcome` (`COMPENSATED`, `COMPENSATION_FAILED` or `IGNORED`), parented on the transport's delivery
context; compensation spans as in 010; the outcome counter counts the terminal status as today.

*Implementation note (2026-11-24):* the plan named the attribute `saga_forge.trigger = command_failed`; the runtime
already records trigger kinds as `saga_forge.compensation.trigger` with enum names, so the reaction reuses that key.

## Tests

Runtime unit tests over `TransactionalInMemoryStorageProvider` and `TestTransport`: each row above; idempotence of a
second report (FR-013); the set-aside rolls back with a conflicted reaction; `UnitOfWorkBoundaryTest` still passes.
