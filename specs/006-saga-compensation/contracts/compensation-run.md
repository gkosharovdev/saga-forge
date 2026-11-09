# Contract: Compensation Run

**Feature**: `006-saga-compensation` | Spec FR-003 to FR-006b, FR-011 to FR-016 | Research §5, §7 to §10

Extends 005's `contracts/attempt-loop.md`. Everything runs in the handling call that triggered it, under the
in-process instance lease the `AttemptLoop` holds.

```text
attempt result (005 loop, extended):
    Committed with compensationStarted       → run remaining (below)
    Compensating (instance found COMPENSATING by the locked read; the event's step did not run)
                                             → saga.compensation.resumed; run remaining
    Failed classified FINAL, or TRANSIENT on the last attempt (EXHAUSTED), or exhausted on resume
                                             → CompensationStartWrite(failure)       -- own transaction
                                                   locked read; terminal → not recorded (as FailedWrite)
                                                   no step COMPLETED → FAILED (005 behaviour)
                                                   else startCompensation(trigger = failed(failure))
                                               then run remaining
    Failed classified UNCLASSIFIED           → FailedWrite (005 behaviour, no compensation)

run remaining:
    loop:
        instance = state after the previous write
        next = selected step with compensation NOT_ATTEMPTED and highest completionSequence
        if none: stop                          -- terminal COMPENSATED was written with the last commit
        result = CompensationUnitOfWork(next).run()
            begin; findByIdAndLock
            status != COMPENSATING or next not NOT_ATTEMPTED → roll back; stop (another process moved it on)
            new saga object; inject @SagaId, SagaContext, CommandGateway; bind recorder (buffer only)
            invoke compensation (with the persisted CompensationTrigger if declared)
            gateway-reported or thrown failure → roll back → Failed
            recordCompensated(next)   [+ COMPENSATED terminal if nothing NOT_ATTEMPTED is left]
            save; IN_TRANSACTION handoff; commit
        Failed      → CompensationFailedWrite(next, classify(cause, default policy))   -- own transaction
                        step FAILED, status REQUIRES_INTERVENTION; stop
        conflict    → stop with NOT_HANDLED (instance stays COMPENSATING; next event resumes)
release lease; AFTER_COMMIT handoff of each committed unit of work's batch, in commit order
return outcome: COMPENSATED | REQUIRES_INTERVENTION | NOT_HANDLED | FAILED (nothing committed to compensate)
```

## Guarantees

- **At most once** (FR-004): compensation starts at most once per instance. The start write requires a non-terminal,
  non-`COMPENSATING` instance under the lock; every later trigger sees `COMPENSATING` (resume) or a terminal status
  (`UNEXPECTED`, or `DUPLICATE` for a repeated failure-outcome event).
- **Selection and order** (FR-005, Property 33): exactly the `COMPLETED` steps except the triggering
  `@StartCompensation` step, highest `completionSequence` first. Steps without a compensation are
  `NOTHING_TO_COMPENSATE` from the start write on.
- **Isolation and durability** (FR-011, FR-012, Property 34): each compensation's effects, its `COMPENSATED` mark and
  its entries commit or roll back together, and the mark is durable before the next compensation begins. A resumed
  run never repeats a `COMPENSATED` step and never skips a `NOT_ATTEMPTED` one.
- **No forward steps** (FR-014): no forward step runs while `COMPENSATING`. Events recorded by compensation handlers
  are handed off, never queued in-process.
- **No retry** (FR-016, SC-007): a failed compensation is never retried. The instance ends `REQUIRES_INTERVENTION`,
  which is terminal.
- **Write failures**: a failure of `CompensationStartWrite` or `CompensationFailedWrite` propagates like a
  `FailedWrite` failure. Nothing more is recorded, and the instance keeps its previous persisted state.
- **Retries before compensation**: 005 is unchanged for `TRANSIENT` failures with attempts left. They retry, and only
  their exhaustion reaches compensation (FR-006).

## Handling results

| Situation | `HandlingOutcome` |
|---|---|
| Compensation ran to the end | `COMPENSATED` |
| A compensation failed | `REQUIRES_INTERVENTION` |
| Final failure with nothing committed | `FAILED` (005) |
| Write-time conflict in a compensation unit of work | `NOT_HANDLED` |
| Event for a terminal instance | `UNEXPECTED` (004) |
| Repeated failure-outcome event after the end | `DUPLICATE` (005) |

## Diagnostics

| Name | Level | When |
|---|---|---|
| `saga.compensation.started` | `INFO` | after the start write, with the trigger kind and step |
| `saga.compensation.resumed` | `INFO` | an event found the instance `COMPENSATING` |
| `saga.compensation.step` | `INFO` | a compensation committed, or a step was `NOTHING_TO_COMPENSATE` |
| `saga.compensation.failed` | `ERROR` | a compensation failed; the instance requires intervention |
| `saga.compensation.ended` | `INFO` | terminal `COMPENSATED` |
