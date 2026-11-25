# Contract: `CommandReceiver.receiveFailure`

**Module**: `saga-forge-core` (`core.spi`) | **Callers**: the in-memory relay, the fixture's `LoopBackTransport`, later
the Kafka transport | **Implementer**: the runtime (`EngineRuntime`)

```text
void receiveFailure(CommandFailure failure, Consumer<LocalTransaction> inTransaction)
```

## Caller obligations (transport)

- C1. Call only for a command whose `CommandId.sagaScoped()` is `true` and whose failure is `FINAL` or `EXHAUSTED`.
  A command sent outside a saga is set aside by the transport itself, as today (FR-005).
- C2. Call at most once per failed delivery, while the message is still leased, from the delivering thread.
- C3. Do the set-aside only inside `inTransaction`, with the transaction it is given
  (`MessageStore.setAside(tx, ...)`). Do not set the message aside before or after the call.
- C4. If the call throws, treat the delivery as a failed attempt: record it with the normal backoff, do not set the
  message aside. The message is redelivered, the handler runs again, and the report is repeated (at least once,
  FR-008).
- C5. Carry no failure message in the report (FR-006).

## Implementer guarantees (runtime)

- R1. Calls `inTransaction` exactly once per successful return, inside a transaction that commits together with any
  change to the saga instance. If the call returns normally, that transaction committed.
- R2. Writes saga state only through `UnitOfWork` (FR-007; `UnitOfWorkBoundaryTest`).
- R3. Reacts at most once per instance: repeated reports for the same instance after the first reaction are ignored
  (FR-013).
- R4. For an instance it ignores (missing, terminal, compensating for a step-sent command; FR-012), emits a diagnostic
  naming the saga id, command type and reason, and still calls `inTransaction` in a standalone unit of work.
- R5. Throws only for infrastructure failure (storage, conflict); never for an ignored report.
- R6. Holds the instance lock for the reaction and its compensations, as for an event (006, G-006-08).

## Default method

The core default throws `UnsupportedOperationException("<class> does not receive command failures")`, so receivers
compiled before 012 still compile; a transport calling it on such a receiver fails the delivery (C4 applies).

## Tests

- Runtime unit tests through `TestTransport`: R1 to R6, including a conflict that throws and a retried report.
- `TransportAcceptanceSuite`: C1 to C4 against the in-memory store and, with `-Pcontainer-tests`, Postgres.
