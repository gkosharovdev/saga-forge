# Contract: `saga-forge-transport-inmemory`

Package `io.github.gkosharovdev.sagaforge.transport.inmemory`. Depends on `saga-forge-core` only.

## Assembly (plain Java)

```java
InMemoryTransport transport = InMemoryTransport.builder()
        .log(messageLog)                     // required: a core.spi.log.MessageLog
        .transactions(transactionBoundary)   // required: same boundary the engine and the log use
        .executor(executor)                  // optional: host-managed; default an internal pool of `workers` threads
        .workers(4)                          // default 4
        .maxAttempts(10)                     // FR-015
        .backoff(Duration.ofSeconds(1), Duration.ofMinutes(1))
        .leaseDuration(Duration.ofMinutes(5))
        .pollInterval(Duration.ofMillis(500))
        .leaseBatchSize(100)
        .retention(Duration.ofDays(7))       // FR-019
        .retentionSweepInterval(Duration.ofHours(1))
        .payloadClassLoader(classLoader)     // default: context class loader at start()
        .clock(clock)
        .logger(System.getLogger(...))
        .build();

SagaEngine engine = SagaEngine.builder()
        .dispatcher(transport.dispatcher())
        .outbox(transport.outbox())
        /* storage, contextStore, transactions, register(...) as today */
        .build();
engine.start();
transport.start(engine, engine.commandReceiver());   // delivers unconsumed entries, then keeps delivering
...
transport.stop(Duration.ofSeconds(30));               // FR-020
```

`build()` rejects missing `log`/`transactions` and non-positive settings with `IllegalArgumentException`.

## Behaviour

| Surface | Contract |
|---|---|
| `dispatcher()` (`EventDispatcher`) | `dispatch(event)`: if a transaction is active on the thread (`transactions.current()`), appends in it; otherwise appends in a new transaction and commits, then signals the relay. A failed append propagates to the caller and nothing is delivered (FR-002). `registerSagaType` is a no-op (all events go to the one engine). |
| `outbox()` (`Outbox`) | `timing()` = `IN_TRANSACTION`; `supportsCommands()` = true; `store(batch)` appends one entry per event then per command in `tx`; `committed(batch)` signals the relay. |
| `start(EventReceiver, CommandReceiver)` | Once only (`IllegalStateException` otherwise). Writes heartbeat, warns about other live consumers (FR-021), starts relay and retention sweeper. |
| Relay | Leases (signal or poll), dispatches entries to workers keyed by ordering key; an event goes to `EventReceiver.receive` then `markConsumed`; a command goes to `CommandReceiver.receive` with an `inTransaction` that marks it consumed in the handler's transaction. Exception → `recordFailure` with backoff, or `setAside` at `maxAttempts`; `UnreadableMessageException` → `setAside(UNREADABLE)`. |
| `stop(timeout)` | Stops leasing, waits up to `timeout` for running deliveries, releases remaining leases, stops sweeper and heartbeat. Idempotent. |
| `queries()` (`MessageLogQueries`) | `find(MessageQuery)`, `redeliver(messageId)` (FR-022, FR-023), `status()` (counts per status, oldest unconsumed age). |
| Listener hook | `onDelivered(Consumer<LoggedMessage>)` — called after an event entry is consumed; used by the Spring bridge (FR-027). Exceptions are logged and ignored. |

## Logging (System.Logger, as the engine)

- INFO: start (consumer id, unconsumed count), stop.
- WARNING: another live consumer; entry set aside (message id, type, reason); lease lost.
- DEBUG: lease batch sizes, each delivery outcome.
