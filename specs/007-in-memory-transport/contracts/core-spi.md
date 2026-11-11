# Contract: Core additions (`saga-forge-core`)

All additions are JDK-only (Principle I). Apart from the rename below, existing signatures keep working (FR-025).

## Rename (research §14)

`EventHandoff` → `Outbox` (`handOff` → `store`), `HandedOffEvents` → `OutgoingMessages`, `DispatchingEventHandoff` →
`ImmediateDispatchOutbox`, `SagaEngine.Builder.handoff` → `outbox`, `SagaHandlingResult.handoffFailures` →
`outboxFailures`. Contracts below use the new names.

## `@Command(delivery = ...)` and `CommandDelivery`

```java
public @interface Command {
    CommandDelivery delivery() default CommandDelivery.IN_UNIT_OF_WORK;
}

public enum CommandDelivery {
    /** Handled synchronously inside the sender's unit of work (002 behaviour). */
    IN_UNIT_OF_WORK,
    /** Appended to the message log with the sender's unit of work; handled after it commits, in its own unit of work. */
    LOGGED
}
```

- **R23** (`validateAll`): each `LOGGED` command type among the classes passed to `validateAll` is handled by a
  `@CommandHandler` of a passed-in host; message `"Command {commandType} is delivered LOGGED but has no @CommandHandler"`.
  Steps do not declare the commands they send, so a `LOGGED` type not passed in is not seen here; `send` therefore
  also resolves the handler before storing a logged command and throws `NoCommandHandlerException` (unit of work
  rolled back) if there is none.
- **Property 35**: a `LOGGED` command sent inside a unit of work is not handled before that unit of work commits, is never
  handled if it rolls back, and its handler receives the `CommandId` assigned at send time.

## `OutgoingCommand` and `OutgoingMessages.commands`

```java
public record OutgoingCommand(CommandId commandId, Object command) { }   // both non-null

public record OutgoingMessages(String unitOfWorkId, String sagaType, String sagaId,
                              List<Object> events, List<OutgoingCommand> commands) {
    public OutgoingMessages(String unitOfWorkId, String sagaType, String sagaId, List<Object> events) {
        this(unitOfWorkId, sagaType, sagaId, events, List.of());
    }
}
```

- `events` and `commands` are unmodifiable copies without null elements; **at least one of them is non-empty** (was:
  `events` non-empty).
- Commands are in send order. With the default `ImmediateDispatchOutbox` (`AFTER_COMMIT`), a batch containing commands
  is a configuration error reported at engine `start()` (`SagaEngineStartupException`): logged commands need an outbox
  that can store them. Detection: R23 found `LOGGED` types and the outbox does not declare `supportsCommands()`.

## `Outbox` additions (on the renamed `EventHandoff`)

```java
default boolean supportsCommands() { return false; }

/**
 * Called after the commit of a unit of work whose batch this outbox stored with Timing.IN_TRANSACTION, after the
 * lock was released, with no engine transaction active. Not called on rollback. A RuntimeException is logged and
 * changes nothing. Not called for AFTER_COMMIT outboxes (store already runs after commit).
 */
default void committed(OutgoingMessages batch) { }
```

## `CommandReceiver` and `LoggedCommand`

```java
public interface CommandReceiver {
    /**
     * Runs the single @CommandHandler for delivery.command() in a new non-saga unit of work, with delivery.commandId()
     * bound for a CommandId parameter. Calls delivery.inTransaction() after the handler returned and before commit, in
     * that transaction. Events recorded by the handler go to the outbox as for any non-saga unit of work.
     *
     * @throws IllegalStateException     engine not started, or a unit of work active on this thread
     * @throws NoCommandHandlerException no handler (only possible if R23 was bypassed)
     * @throws RuntimeException          anything the handler, loader, saver or inTransaction threw; rolled back
     */
    void receive(LoggedCommand delivery);
}

public record LoggedCommand(CommandId commandId, Object command, Consumer<LocalTransaction> inTransaction) { }
```

Implemented by the engine (`SagaEngine.commandReceiver()`).

## `core.spi.log.MessageLog`

Storage-agnostic; implemented by storage modules (and the interop decorator in the starter). All methods that take no
`LocalTransaction` run in a transaction of their own through the implementation's `TransactionBoundary`.

```java
public interface MessageLog {
    /** Appends in tx (FR-001/FR-002); assigns positions in list order. Duplicate messageId → DuplicateMessageException. */
    void append(LocalTransaction tx, List<LogEntry> entries);

    /** Leases up to limit leasable entries (data-model invariants), ordered by position; sets lease and attempts+1. */
    LeasedMessages lease(String consumerId, Instant now, Duration leaseDuration, int limit);

    /** Extends the lease of entries still held by consumerId; returns the ids no longer held. */
    Set<String> renew(String consumerId, Collection<String> messageIds, Instant until);

    void markConsumed(String consumerId, String messageId, Instant at);                       // own transaction
    void markConsumed(LocalTransaction tx, String consumerId, String messageId, Instant at);  // inside a command's UoW
    void recordFailure(String consumerId, String messageId, DeliveryFailure failure, Instant nextAttemptAt);
    void setAside(String consumerId, String messageId, DeliveryFailure failure, String reason);

    /** Releases every lease held by consumerId (shutdown) — entries become leasable again. */
    void release(String consumerId);

    List<LoggedMessage> query(MessageQuery query);
    /** SET_ASIDE → UNCONSUMED, attempts 0 (FR-023); false if not set aside. */
    boolean redeliver(String messageId, Instant now);
    /** Deletes CONSUMED entries consumed before cutoff, at most batchSize; returns the number deleted. */
    int purgeConsumed(Instant cutoff, int batchSize);

    void heartbeat(ConsumerHeartbeat heartbeat);
    List<ConsumerHeartbeat> liveConsumers(Instant since);
}
```

- A leaseholder write by a consumer that no longer holds the lease throws `LeaseLostException`; the relay logs it and
  drops the result (the entry is redelivered — at-least-once).
- `LoggedMessage.payload()` decoding failure surfaces as `UnreadableMessageException` (carrying the entry without
  payload) from `lease`, so the relay can set it aside (research §6).
- Records: `LogEntry`, `LoggedMessage`, `DeliveryFailure`, `MessageQuery` (status set, source saga type/id, ordering key,
  payload type, limit ≤ 1,000, `includePayload`), `ConsumerHeartbeat`; enums `MessageKind`, `MessageStatus` — fields per
  data-model.md.
