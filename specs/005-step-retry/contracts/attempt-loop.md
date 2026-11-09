# Contract: Attempt Loop

**Feature**: `005-step-retry` | Spec FR-013 to FR-016 | Research §8 to §10

Applies to every event routed to an existing or just-created saga instance (004 `EventRouter` → unit of work).

```text
lease = in-process instance lock               (held until the loop ends)
k = 1 ; started = creation write committed for this event
loop:
    result = SagaUnitOfWork(trigger, attempt k, started).run()
        - locked read; DUPLICATE / UNMATCHED / UNEXPECTED / NOT_HANDLED → return result
        - if instance.pendingRetry.triggerStep == trigger step and this is the first attempt of this call:
              roll back (Resume); k = pendingRetry.attemptsStarted + 1
              if k > maxAttempts(pendingRetry.lastFailure.stepName):
                  FailedWrite(EXHAUSTED, attempts = k - 1, cause of lastFailure); return FAILED
              AttemptWrite claim: pendingRetry.attemptsStarted = k (no history entry); run attempt k
        - run steps; commit (pendingRetry cleared when its triggerStep == trigger step;
          completed steps get attempts = k) → return WAITING / COMPLETED
        - failure → roll back, classify (contracts/retry-policy.md) → return Failed(classification, F, cause)
    if Failed is TRANSIENT and k < maxAttempts(F):
        AttemptWrite: pendingRetry = (trigger step, k + 1, SagaFailure(F, cause, TRANSIENT, k)),
                      entry ATTEMPT_FAILED(F, that failure)         -- own transaction
        pause(wait(k + 1))                                          -- RetryPause
            interrupted → restore flag; return NOT_HANDLED (claimed attempt stays recorded)
        k = k + 1 ; started = false ; continue
    else:
        FailedWrite: status FAILED, SagaFailure(F, cause, TRANSIENT→EXHAUSTED | FINAL | UNCLASSIFIED, k)
        return FAILED
finally: release lease (before the AFTER_COMMIT handoff of a committed attempt)
```

## Guarantees

- One unit of work per instance in-process for the whole loop (FR-014); across processes, one at a time, with the
  database lock released between attempts.
- Each attempt is a new unit of work (new `unitOfWorkId`), re-running the triggering event from the start; queued and
  buffered events of a failed attempt are discarded (004).
- `SagaContext` values written by a failed attempt are visible to the next (they are outside the transaction).
- Attempts of one event never exceed `maxAttempts` of the failing step, including across restarts after the first
  failed attempt (research §9).
- An `AttemptWrite` failure (store unavailable) ends the loop like a `FailedWrite` failure: the cause propagates to the
  caller and nothing further is recorded; it is never retried blindly.

## Diagnostics (004 `System.Logger`, new event names)

| Name | Level | When |
|---|---|---|
| `saga.uow.retrying` | `INFO` | after an `AttemptWrite`, with step, attempt, wait, cause type |
| `saga.uow.exhausted` | `WARNING` | `FAILED` with `EXHAUSTED` |
| `saga.uow.retryInterrupted` | `WARNING` | pause interrupted |
| `saga.event.duplicate` | `DEBUG` | `DUPLICATE` outcome |
| `saga.command.skipped` | `INFO` | already-handled command not re-handled |
