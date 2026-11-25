# Contract: Outbox scheduling and `CommandGateway.schedule` (FR-007, FR-008, FR-016)

## Outbox

- `Outbox.supportsScheduling()` default `false`. `ImmediateDispatchOutbox`: `false`. In-memory transport outbox:
  `messageStore.supportsScheduling()`; it is `IN_TRANSACTION`.
- `OutgoingMessages.scheduled()`: an `IN_TRANSACTION` outbox appends each `ScheduledMessage` as a `NewMessage`
  (`notBefore`, no ordering key, `sourceSagaType`/`sourceSagaId` of the batch, trace context of the unit of work) in the
  unit of work's transaction.
- The runtime never builds a batch with `scheduled` entries for an outbox whose `supportsScheduling()` is `false` (the
  startup check below makes that unreachable for deadlines; `schedule` throws before).

## Runtime startup (FR-016, D4, D5)

`SagaRuntime.start()` throws `SagaRuntimeStartupException` when any saga in the model declares `@StartDeadline` and the
outbox's `supportsScheduling()` is `false`. Message:

```
Saga com.example.CheckoutSaga declares deadline 'paymentWindow', but the outbox (ImmediateDispatchOutbox) cannot
schedule messages. Deadlines need an IN_TRANSACTION outbox over a message store that supports scheduling, such as
InMemoryTransport with PostgresMessageStore.
```

Starter: `DeadlinesRequireOwnLogFailureAnalyzer` recognises this failure when Modulith interop is on and adds
"Set saga-forge.transport.modulith-interop=false to keep Saga Forge's own message log."

Unresolvable `${key}` or unparseable fixed duration ⇒ `SagaRuntimeStartupException` naming saga, deadline, value.

## `CommandGateway`

```java
ScheduledCommandId schedule(Object command, Instant at);
boolean cancel(ScheduledCommandId id);
```

| Situation | Behaviour |
|-----------|-----------|
| No current unit of work | `IllegalStateException` (as an outbox `send` outside one) |
| Outbox does not support scheduling | `UnsupportedOperationException` |
| Command type not `@Command(delivery = OUTBOX)` | `IllegalArgumentException` |
| `command` or `at` null | `NullPointerException` (`Objects.requireNonNull`, named) |
| `at` in the past | due at once |
| Saga step caller | `CommandId` saga-scoped (sagaId, step) ⇒ 012 failure feedback applies when it fails |
| Unit of work rolls back | nothing scheduled, a `cancel` in it is undone |
| `cancel` of an id scheduled in the same unit of work | removed from the batch, returns `true` |
| `cancel` of a pending stored id | written in the unit of work's transaction; returns `true` iff the store changed it |
| `cancel` of a leased, consumed, cancelled or unknown id | `false` |

Message id of a scheduled command: `<unitOfWorkId>:scheduled:<n>`; `ScheduledCommandId.value()` returns it.

## `Outbox.cancel`

`default Set<String> cancel(Collection<String> messageIds)` throws `UnsupportedOperationException`. An
`IN_TRANSACTION` outbox that supports scheduling implements it as
`messageStore.cancel(transactionBoundary.current(), messageIds, clock.instant())`. The runtime calls it inside the unit
of work's transaction, both for `CommandGateway.cancel` (which needs the answer) and for deadline cancellations
(research R7), so there is one cancel path. Called outside a transaction ⇒ `IllegalStateException`.
