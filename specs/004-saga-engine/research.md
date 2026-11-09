# Phase 0 Research: Saga Engine Runtime

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md)

Each section settles one design question the spec left to planning. Format: Decision, Rationale, Alternatives
considered. Facts about existing code were checked against `saga-forge-core`, the 002 harness
(`saga-forge-core/src/test/.../execution/harness/`) and `saga-forge-storage-postgres` on branch `004-saga-engine`.
Decisions marked **(confirm)** go beyond what spec.md states and need the user's sign-off before tasking.

## 1. Where the engine lives **(confirm)**

**Decision**: A new module, `saga-forge-engine`, depending on `saga-forge-core` only (JDK otherwise). It holds the
runtime: `SagaEngine` and its builder, the unit-of-work machinery, and the production `SagaContext`,
`SagaLifecycle`, `CommandGateway` and `EventPublisher` implementations. Every type a storage, transport or host
module must implement or call without depending on the engine (the transaction boundary, the inbound entry point
and its report, the after-commit handoff, the concurrency-conflict exception, the host plug-ins) is a contract in
`saga-forge-core`'s `spi` package, next to `StorageProvider` and `EventDispatcher`. The starter will depend on
`saga-forge-engine`; transports and storage modules keep depending on `saga-forge-core` only. The root `pom.xml`
gains the module after `saga-forge-core`; AGENTS.md's Module Map, README's Modules table and `docs/architecture.md`
"Module responsibilities" gain an engine entry (Principle V).

**Rationale**:
- Constitution Principle I defines core as "annotations, scanner, `SagaContext`/`SagaLifecycle`, SPI contracts".
  An engine is none of these; putting it in core turns core from a contract module into a runtime.
- G-GOV-01 lists SLF4J and the JTA API as likely future dependencies of the engine (logging, transaction
  contract). A separate module can take them later without touching core's "zero dependencies" posture, which
  AGENTS.md states without exceptions.
- Transports and storage modules only need contracts. With the contracts in core, a Kafka transport or the
  Postgres module never depends on the engine, so there is no cycle and no transitive runtime they don't use.
- Principle IV is not engaged: the module is neither a storage nor a transport, and it ships no in-memory storage
  or transport in `src/main` (test doubles only, §14).

**Alternatives considered**: A `core.engine` package in `saga-forge-core` (rejected: widens Principle I's core
scope, puts later SLF4J/JTA adoption into core, and makes every transport and storage module carry the runtime).
Putting the engine inside `saga-forge-spring-boot-starter` (rejected: FR-037 and the "non-Spring runtime" goal in
`docs/architecture.md`; the engine must be usable and unit-testable without Spring). Engine-only SPIs inside the
engine module (rejected for consistency: `HandlerInstanceProvider` and `AggregateResolver`, also host-only, are
already in `core.spi`).

## 2. Transaction-boundary contract (FR-020, FR-021, FR-022; resolves G-ENG-03)

**Decision**: Two `core.spi` interfaces, both thread-bound like `DomainEventScope`:

```java
public interface TransactionBoundary {
    LocalTransaction begin();                      // a new top-level local transaction on this thread
    Optional<LocalTransaction> current();          // the one begun through this boundary, if active here
    default boolean isActive() { return current().isPresent(); }
}
public interface LocalTransaction {
    void commit();
    void rollback();
    void setRollbackOnly();
    boolean isRollbackOnly();
    <R> Optional<R> resource(Class<R> type);       // the transaction's resource of that type, e.g. a JDBC Connection
}
```

`begin()` never joins an active transaction: if one is active on the thread, an implementation either suspends it
until the new one ends or throws `IllegalStateException` (the test double throws). There is no nesting, no
savepoint and no two-phase commit. The engine never calls `commit()` on a rollback-only transaction; an
implementation asked to do so rolls back and throws `IllegalStateException`.

Operation mapping, with no dependency on either API:

| Contract | JTA (`jakarta.transaction`) | Spring (`PlatformTransactionManager`) |
|---|---|---|
| `begin()` | `TransactionManager.begin()` (`UserTransaction.begin()`); an active transaction is first `TransactionManager.suspend()`ed and `resume()`d at the end, or rejected (`NotSupportedException`) | `getTransaction(def)` with `PROPAGATION_REQUIRES_NEW` (suspends an outer one), keeping the returned `TransactionStatus` in the `LocalTransaction` |
| `commit()` | `commit()` | `commit(status)` |
| `rollback()` | `rollback()` | `rollback(status)` |
| `setRollbackOnly()` | `setRollbackOnly()` | `status.setRollbackOnly()` |
| `isRollbackOnly()` | `getStatus() == Status.STATUS_MARKED_ROLLBACK` | `status.isRollbackOnly()` |
| `isActive()` / `current()` | `getStatus() != Status.STATUS_NO_TRANSACTION` | `TransactionSynchronizationManager.isActualTransactionActive()` |
| `resource(type)` | the enlisted resource (a JTA-managed `DataSource.getConnection()`), or `TransactionSynchronizationRegistry.getResource(key)` | `DataSourceUtils.getConnection(dataSource)` / `TransactionSynchronizationManager.getResource(key)` |

**Joining (FR-022)**: a storage provider built with the host's `TransactionBoundary` looks up the active
transaction and asks it for its resource by type, e.g. `current().flatMap(tx -> tx.resource(Connection.class))`
in the Postgres module. When a transaction is active it uses that resource and never commits, rolls back,
changes `autoCommit` on, or closes it; when none is active it keeps 003's own-transaction behavior. Aggregate
access joins because every loader, saver, resolver and handler runs synchronously on the unit-of-work thread
while the transaction is active: under Spring, JPA and JDBC repositories join through Spring's own resource
binding; a plain-JDBC host reads the same `resource(Connection.class)`. `SagaContextStore` is never built with the
boundary and keeps its own connection (FR-022, 003 research §6).

For hosts without Spring or JTA, and for the tests, `saga-forge-storage-postgres` ships `JdbcTransactionBoundary`
(JDBC only): `begin()` takes a `Connection` from the `DataSource`, sets `autoCommit = false` and binds it to the
thread; `commit`/`rollback` end it, restore `autoCommit` and close it; `resource(Connection.class)` returns it.

**Rationale**: The five operations FR-020 lists exist in both APIs with the same meaning. A handle-based
`LocalTransaction` maps onto Spring's `TransactionStatus` directly and onto JTA's thread-bound transaction by
ignoring the handle. Looking resources up by Java type keeps JDBC (or a MongoDB `ClientSession`) out of core while
giving storage modules and a later outbox one storage-agnostic way to join. A local transaction has one resource
of a kind, so the type is an unambiguous key.

**Alternatives considered**: A static thread-bound resource registry in core, modelled on Spring's
`TransactionSynchronizationManager` (rejected: hidden global state, `Object`-typed keys and values, and every
bridge would have to translate holder types such as Spring's `ConnectionHolder`). A storage-module-local
"connection supplier" interface with no core counterpart (rejected: an outbox in the Kafka feature would then
depend on a storage module to join the transaction, against FR-032). Transaction synchronization callbacks
(`beforeCommit`/`afterCommit`) in the contract (rejected: the engine calls `commit()` itself and sequences
everything around it; nothing needs a callback). Supporting nested transactions or savepoints, for example to
write `FAILED` inside the rolled-back transaction (rejected: FR-021, JTA has no savepoints).

## 3. One unit of work per instance (FR-025 to FR-028)

**Decision**: Two locks, both taken before any step runs or any `SagaContext` value is written.

- **In-process**: a per-`sagaId` lock (a fair `ReentrantLock` in a reference-counted map, removed when unused),
  held from before the unit of work's transaction begins until its commit or rollback and, on failure, until the
  separate `FAILED` write has finished. It is released *before* the after-commit handoff, so a synchronous
  transport that delivers back into the entry point runs the next unit of work after this one (FR-007, US5-5).
  Different `sagaId`s use different locks (FR-028). A start event additionally takes a per-(saga type,
  association property, association value) lock around the duplicate check and the creation write (FR-027).
- **Cross-process**: a new `StorageProvider` operation, `Optional<SagaInstance> findByIdAndLock(String sagaId)`,
  called as the first statement of the unit of work's transaction. It reads the instance and holds an exclusive
  lock on it until that transaction ends; the same call in another transaction blocks until then. It requires an
  active transaction (`IllegalStateException` otherwise). After it returns, the engine re-checks the instance:
  missing → unmatched; terminal → unexpected; still `CREATED` while the event is not the start step's (the start
  unit of work is pending, possibly in another process) → not handled (§6). Only then does it load the
  `SagaContextRecord` and run the triggered step.
  - In-memory test double: a per-`sagaId` lock owned by the active test transaction and released when it commits
    or rolls back; two engine instances sharing the double model two processes (§14).
  - Postgres: `SELECT ... FROM saga_instance i WHERE i.saga_id = ? FOR NO KEY UPDATE OF i` on the joined
    connection. `FOR NO KEY UPDATE`, not 003's `FOR UPDATE`: `PostgresSagaContextStore` writes on its own
    connection while the unit of work holds the lock, and its `saga_context` foreign-key check takes
    `FOR KEY SHARE` on the instance row, which `FOR UPDATE` blocks and `FOR NO KEY UPDATE` does not. With
    `FOR UPDATE` the unit-of-work thread would wait on its own lock forever, invisible to Postgres's deadlock
    detection (two connections, one thread). 003's `LOCK_PREVIOUS` in `save` moves to `FOR NO KEY UPDATE` too; its
    upsert never updates `saga_id`, so it is a no-key update. Reasoned from the Postgres lock-conflict table, not
    run (G-ENG-11, G-003-23).
- **Write-time conflict (FR-026)**: a new unchecked `core.spi.SagaConcurrencyConflictException` (`sagaId()`)
  is what a storage provider throws when a write detects a concurrent change; Postgres's
  `ConcurrentSagaUpdateException` (forward-transition check, lost first save) extends it. The engine rolls the
  unit of work back, writes no `FAILED`, and reports the event `NOT_HANDLED` for that instance so the transport can
  redeliver it. It does not retry in-process.

**Rationale**: FR-025 says a waiting unit of work runs no step and writes no context before the first one
finished, so the lock must come first. 003's row lock is taken inside `save`, at the end of a unit of work, after
steps ran and context was written, which is too late. A pessimistic lock at load fits Postgres and MySQL
(`SELECT ... FOR UPDATE`) and has a MongoDB counterpart (a locking update inside a session transaction). With the
lock taken first, a conflict at write time is not expected from a correct provider; when one happens anyway, an
in-process retry would re-run steps whose context writes and non-transactional side effects already happened, so
handing the event back to the transport is the more honest outcome.

**Alternatives considered**: An optimistic version on `SagaInstance` checked at write (rejected: the losing unit of
work has already run its steps and written context, contrary to FR-025, and it changes `SagaInstance`'s shape for
every store). In-process locking only (rejected: FR-025 covers processes sharing storage). Retrying once against
fresh state (rejected, see Rationale; retry belongs to the step-retry design). A default `findByIdAndLock` that
calls `findById` (rejected: a provider that forgot to implement it would silently lose cross-process
serialization).

## 4. `StorageProvider` write contract and lifecycle entries (FR-023, FR-033; resolves G-ENG-02) **(confirm)**

**Decision**:

- New write operation `void save(SagaInstance instance, List<SagaLifecycleEntry> newEntries)`: persists the
  instance (create or update) and appends `newEntries`, in list order, after every entry already recorded for the
  instance, in one transaction (the active one when joined, else its own). Every entry's `sagaId` must equal
  `instance.sagaId()` (`IllegalArgumentException`). The engine calls it three ways: the creation write
  (`[CREATED]`), a unit of work's write (`STEP_COMPLETED`... then `COMPLETED` if it ended), the `FAILED` write
  (`[FAILED]`).
- `save(SagaInstance)` stays as a `default` method meaning `save(instance, List.of())`. Its 003 behavior of
  deriving history by diffing is removed, so a direct caller records no history.
- **Storage assigns the sequence number.** `SagaLifecycleEntry` loses its `sequenceNumber` component (a core
  record change). Storage numbers the entries when it inserts them; Postgres keeps the `sequence_number` column
  and its `(saga_id, sequence_number)` key, computes `MAX + 1` under the instance lock and numbers the batch
  consecutively. Order in the persisted history is therefore exactly the order the engine handed over (FR-033).
- `LifecycleHistoryDerivation.derive` is deleted. `requireForwardTransition` stays (defense in depth against a
  caller that bypasses the engine), moved into a class named for what it does (`ForwardTransition`), and its lock
  query moves to `FOR NO KEY UPDATE` (§3). G-003-04 and G-003-16 close with the derivation; G-003-01 cannot arise
  for engine-driven instances; the derivation half of G-003-15 goes away.
- Javadoc of `SagaLifecycleEntry` and `SagaLifecycleEventKind` is rewritten: entries are produced by the engine and
  handed to storage with the instance write they belong to; `CREATED` is the creation write; order is the
  hand-over order; no tiebreak, no first-save limitation (G-ENG-02).

**Rationale**: The engine reads no history (FR-014, and there is no query API, G-003-24), so it cannot know the
next number without a new read or a counter on `SagaInstance`. Storage already holds the instance lock when it
inserts, so numbering there is race-free and needs no shape change elsewhere. The component is removed rather
than ignored so the type cannot carry a number nobody honors. Nothing outside storage modules and their tests
reads `sequenceNumber` today (checked: `PostgresStorageProvider.insertHistory`, `LifecycleHistoryDerivation`,
`SagaLifecycleEntryTest`, `LifecycleHistoryDerivationTest`, `LifecycleHistoryTest`).

**Alternatives considered**: Engine-assigned numbers from a counter added to `SagaInstance` (rejected: a shape change
for every store just to number history). Keeping `sequenceNumber` and letting storage overwrite it (rejected: a
value that lies until persisted). A second record type for unsequenced entries (rejected: two types for one
concept). A separate `appendHistory(...)` operation (rejected: FR-033 requires the entries in the same write as the
instance; two calls invite a caller to forget one). A later query API can return entries with their stored number
in a wrapper without changing this type.

### 4a. A generic mapping seam parameterized by the storage representation

Evaluated at the user's request: a mapper from a core value to a store's representation `R`, for example
`LifecycleEntryMapper<R>`, or more broadly `RecordMapper<T, R> { void write(T value, R target); T read(R source); }`
covering instances, steps and entries.

- **Not on `StorageProvider`.** A `StorageProvider<R>` would reach the engine only as `StorageProvider<?>`: the
  engine never produces or consumes an `R`, so the parameter would add a wildcard to every wiring point and say
  nothing. The engine-facing write contract stays representation-free (FR-033).
- **Shapes differ per store.** JDBC writes by binding parameters (`R = PreparedStatement` as a sink, read back
  from a `ResultSet`); MongoDB builds a value (`R = org.bson.Document`, both directions). A shared signature
  fits both only in the sink form `write(value, target)`, which is awkward for document stores that return new
  values.
- **Erasure.** `R` is gone at runtime, so a core interface would buy compile-time consistency inside one module
  only, which a concrete mapper class already gives.
- **No core caller.** A core type that no core or engine code calls is dead SPI surface (the same reasoning as 003
  research §2).

**Recommendation**: no generic type in core. Each storage module keeps concrete, package-private mappers: the
Postgres module gets `LifecycleEntryRows` (bind one entry to the history insert; extracted from `insertHistory`)
next to the existing `SagaInstanceRows`, and a later MongoDB module would add `LifecycleEntryDocuments` mapping an
entry to a `Document`. Being pure, they are unit-testable without a database (§15). Revisit a shared
`<T, R>` helper when MySQL lands, where a shared JDBC support module (G-ENG-09) would be the natural home.

## 5. After-commit handoff (FR-029 to FR-032)

**Decision**: A `core.spi.EventHandoff` interface receiving one `HandedOffEvents` batch per committed unit of work:

```java
public interface EventHandoff {
    enum Timing { AFTER_COMMIT, IN_TRANSACTION }
    default Timing timing() { return Timing.AFTER_COMMIT; }
    void handOff(HandedOffEvents batch);
}
public record HandedOffEvents(String unitOfWorkId, String sagaType, String sagaId, List<Object> events) { }
```

- **Which events**: exactly the events recorded in the unit of work that no step of the owning instance ran
  in-process, including those still queued when an `@EndSaga` step ended it; for a non-saga unit of work, every
  recorded event. Each event once. Nothing for a rolled-back unit of work. The engine calls `handOff` only when the
  batch is non-empty.
- **Order**: recording order within the unit of work.
- **Identity**: `unitOfWorkId`, unique per unit of work (saga-scoped or not), plus the saga type (fully qualified
  class name, as in `SagaInstance.sagaType`) and saga id for a saga-scoped unit of work; both `null` for a non-saga
  one. `(unitOfWorkId, index in events)` identifies an event uniquely, which a later outbox can use as a key.
- **When**: `AFTER_COMMIT` (default) is called after the commit, after the in-process lock is released, on the
  unit-of-work thread, with no transaction active. `IN_TRANSACTION` is called after the instance write and before
  `commit()`, inside the unit of work's transaction, so an outbox writes through `TransactionBoundary.current()`
  and its rows commit or roll back with the unit of work (US5-7, SC-011).
- **Default**: `DispatchingEventHandoff` (engine module, `AFTER_COMMIT`) calls `EventDispatcher.dispatch` for each
  event in order. A failure of one event is collected and the rest are still attempted; every failure is listed in
  the handling report (`handoffFailures`) and logged (FR-031). This is the at-most-once guarantee of FR-030 and the
  known loss window of G-ENG-07.
- **Failures**: an `AFTER_COMMIT` failure never touches the committed unit of work or marks the saga `FAILED`. An
  `IN_TRANSACTION` failure is a failure of the unit of work: rollback, then the `FAILED` write, with the step that
  ran last as `SagaFailure.stepName` (FR-031, confirming the spec's choice).
- **Ids and time**: `unitOfWorkId` comes from a host-replaceable `Supplier<String>` (default random UUID), like saga
  ids; timestamps from a host-replaceable `java.time.Clock` (default system UTC).

**Rationale**: One interface with a timing switch lets the Kafka feature swap in an outbox without an engine change
(FR-030, FR-032): the engine already knows both points in the commit sequence. Calling the after-commit handoff with
the lock released and no transaction active is what makes synchronous redelivery a fresh unit of work. Collecting
per-event failures keeps one bad event from silently dropping the rest of the batch.

**Alternatives considered**: Two interfaces, one per timing (rejected: an implementation would have to be registered
in the right slot and the engine would need a rule for both being present). Per-event callbacks (rejected: the
outbox needs the batch and its identity together). Calling `handOff` for empty batches (rejected: noise for an
outbox; "exactly its events" holds trivially). Reusing `EventDispatcher` as the handoff (rejected: it carries no
unit-of-work identity and has no in-transaction variant). Spring-style transaction synchronization for the
after-commit call (rejected, §2).

## 6. Inbound entry point and handling report (FR-001 to FR-007)

**Decision**: `core.spi.EventReceiver`, the inbound counterpart of `EventDispatcher`, implemented by `SagaEngine`:

```java
public interface EventReceiver { HandlingReport receive(Object domainEvent); }
public record HandlingReport(Object event, List<SagaHandlingResult> results) { boolean ignored(); }
public record SagaHandlingResult(String sagaType, String sagaId, HandlingOutcome outcome, boolean started,
        String outcomeStep, SagaFailure failure, String unitOfWorkId, List<Throwable> handoffFailures) { }
public enum HandlingOutcome { WAITING, COMPLETED, FAILED, DUPLICATE_START, UNMATCHED, UNEXPECTED, NOT_HANDLED }
```

- **Ignored**: no registered saga type has a step for the event's exact class → `results` empty, `ignored()` true.
- **Fan-out**: for each saga type in registration order with a step for the event's class: a start step runs one
  start unit of work (`started = true`, or `DUPLICATE_START`); a non-start step resolves the correlated instances
  of that saga type through `findByAssociation` and runs one unit of work per active instance, in the order the
  provider returns them (G-003-14), each committed or rolled back independently. Only instances (never steps of
  other saga types) are affected by a result.
- **Validation first (FR-006)**: `null`, non-`@DomainEvent`, or a missing/empty association value for any reacting
  step → `IllegalArgumentException` before any unit of work runs, so the event has no effect at all.
- **Errors (FR-005)**: if a unit of work could record no outcome (storage unavailable, creation write or `FAILED`
  write failed, lock wait aborted by the store), the engine finishes the remaining instances, then throws
  `core.spi.EventHandlingException` carrying the partial `HandlingReport` and the causes, so the transport can
  redeliver. A saga ending `FAILED` is a result, not an error.
- **Not handled**: `NOT_HANDLED` covers a write-time conflict (§3) and an event for a non-start step whose instance
  is still `CREATED` (its start unit of work has not committed, §3, G-ENG-05).
- **Reentrancy (FR-007)**: the engine keeps a thread-bound "unit of work running" marker. `receive` while it is set
  (from a step, a handler, or an `IN_TRANSACTION` handoff) throws `IllegalStateException`, which fails that unit of
  work like any other failure. The marker is cleared before the `AFTER_COMMIT` handoff, so a synchronous transport
  may call `receive` from inside it and gets a new unit of work.
- **Not started**: `receive` before a successful `start()`, or after a refused one, throws `IllegalStateException`
  and processes nothing (FR-036, US6-5).

**Rationale**: Transports depend on core only, so the entry point and report types must be in core. One result per
saga type and instance, with the instance's outcome step or failure, is what US5-1 asks a transport to see.
Representing "ignored" as no results avoids a result row with no saga type.

**Alternatives considered**: Throwing for unmatched/unexpected/ignored (rejected: FR-004 says none is an error).
Stopping fan-out at the first error (rejected: other instances' units of work are independent; the transport
redelivers either way, and duplicates under redelivery are the Kafka feature's idempotency work). An asynchronous
`receive` returning a future (rejected: the spec's threading assumption, one unit of work on the calling thread).
Treating an event on a `CREATED` instance as a normal step (rejected: it would run a non-start step before the start
step in the cross-process window between the creation write and the first unit of work's lock).

## 7. Creation write and `FAILED` write (FR-011, FR-012, FR-024; resolves G-ENG-01)

**Decision**: Each in its own transaction through `TransactionBoundary`:

1. **Creation write** (start step): under the duplicate-start lock and the new instance's in-process lock,
   check `findByAssociation` for an active instance of the saga type (→ `DUPLICATE_START`), then
   `SagaLifecycle.start(...)` builds the `CREATED` instance with every step `NOT_RUN`; begin, `save(instance,
   [CREATED])`, commit. Release the duplicate-start lock. A failure here records nothing and is raised (§6).
2. **Unit of work**: begin, `findByIdAndLock`, steps, `save(updated, entries)`, commit (FR-008, FR-010).
3. **`FAILED` write** after a rollback: still holding the in-process lock, begin, `findByIdAndLock`, apply
   `fail(sagaId, failure)` to the instance as now stored, `save(failed, [FAILED])`, commit. If the stored instance
   is already terminal, nothing is written and the failure is logged and reported (`FAILED` result with
   `failure` set, instance unchanged). A failure of this write is raised (FR-005).

The instance therefore exists, committed, before any `SagaContextStore.save` for it: Postgres's `saga_context`
foreign key always finds the row (G-ENG-01), and the first instance write always has every step `NOT_RUN`, so
G-003-01 does not arise.

**Rationale**: FR-012 and FR-024 require exactly this. Locking again for the `FAILED` write keeps it serialized with
other processes' units of work.

**What remains open**: G-ENG-05 (a crash between steps 1 and 2 strands a `CREATED` instance that a redelivered start
event cannot resume; later events for it are `NOT_HANDLED`). G-ENG-10 (new): between the rollback in step 2 and the
lock in step 3 another process can run a unit of work on the same instance; the `FAILED` write then marks an
instance that moved on, or finds it terminal and records nothing.

**Alternatives considered**: Writing `FAILED` in the rolled-back transaction through a savepoint (rejected: §2, no
savepoints). Creating the instance inside the first unit of work, as the harness does (rejected: the context
store's separate connection cannot see an uncommitted row, 003 research §8).

## 8. Production collaborators and the fate of the 002 harness (FR-009, FR-013 to FR-018; SC-001, SC-009)

**Decision**: Each harness class has a production counterpart in `saga-forge-engine` (package-private unless
listed as public):

| 002 harness (test) | Engine (main) | What changes |
|---|---|---|
| `SagaTestHarness` | `SagaEngine` (public) + builder | Real transaction boundary, locks, creation/`FAILED` writes, handoff, startup checks, diagnostics |
| `HarnessUnitOfWork` | `SagaUnitOfWork`, `NonSagaUnitOfWork` | Snapshot/restore replaced by `TransactionBoundary`; entries produced; in-transaction handoff |
| `HarnessSagaLifecycle` (writes storage on every call) | `EngineSagaLifecycle` | Transitions apply to the unit of work's working copy of the instance; written once per write (§7); each transition appends its `SagaLifecycleEntry` |
| `HarnessSagaContext` | `EngineSagaContext` | Same value semantics and write-through after every `putState`/`updateStateAttribute`, over any `SagaContextStore` |
| `HarnessCommandGateway`, `HarnessAggregateAccess` | `EngineCommandGateway`, `AggregateAccess` | Several `AggregateResolver`s (first supporting wins); non-command parameters through `HandlerParameterResolver`s; outside a unit of work opens a non-saga one |
| `HarnessEventPublisher` | `EngineEventPublisher` | Outside a unit of work dispatches immediately and does not loop the event back (the transport does) |
| `HarnessSagaModel`, `HarnessReflection` | `SagaModel`, `Reflection` | Built from the validated model; saga objects from `SagaObjectProvider` (§9) |
| `UnitOfWorkResult`, `HarnessEventLog` | `SagaHandlingResult`, diagnostics (§10) | |

Binding inside a unit of work: the unit of work is the `DomainEventRecorder` bound through
`DomainEventScope.runWith` for its whole run, so `DomainEvents.record` and in-unit-of-work `EventPublisher.publish`
reach it from any depth on its thread (FR-017). A fresh saga object per unit of work gets its `@SagaId`, an
`EngineSagaContext` bound to that `sagaId`, and the engine's `CommandGateway` (FR-009).

The harness is **kept as is**, as the frozen reference model of the 002 contract. `saga-forge-core` publishes its
test classes as a test-jar (`maven-jar-plugin` `test-jar` goal), and `saga-forge-engine` depends on it in test scope.
Engine tests reuse the 002 fixtures (`ThreeStepSaga`, `CreateBookingSaga` and their handlers, repositories and
events) unchanged (SC-009) and prove SC-001 with **differential tests**: the same event sequence runs on a
`SagaTestHarness` and on a `SagaEngine` with the same clock and saga-id supplier, and the tests compare status, step
records, outcome, failure, aggregate state, context values and dispatched events. The 002 harness tests stay in
core and keep testing the harness. The harness's `InMemoryStorageProvider` gains the two new SPI methods (§4) and is
otherwise unchanged. Retiring the harness once the engine is the reference is a follow-up (G-ENG-13).

**Rationale**: Core's tests cannot depend on the engine (cycle), so "re-pointing" the 002 tests means running their
scenarios against the engine from the engine module. A differential test states SC-001 literally ("produce the same
... when the same sagas run on the engine"). A test-jar is an internal artifact, not a third-party dependency
(SC-008).

**Alternatives considered**: Rewriting the harness as a thin fixture over the engine (rejected: impossible from core
without a cycle; moving it means moving every 002 test). Moving the 002 fixtures and tests into the engine module
(rejected: churn across forty test methods for no behavioral gain, and the fixtures would no longer sit next to the
contract they illustrate). Copying the fixtures (rejected: two copies drift, and SC-009 is about the originals).

## 9. Startup validation and host plug-ins (FR-019, FR-034 to FR-036)

**Decision**: `SagaEngine.builder()` collects the registered classes and plug-ins; `build()` assembles; `start()`
validates and either starts or refuses:

1. `ReflectiveAnnotationScanner.validateAll(registered classes)`.
2. Engine checks, all collected in the same pass:
   - every `@Aggregate` with instance-method `@CommandHandler`s has a client `@AggregateLoader` among the registered
     classes or a registered `AggregateResolver` whose `supports(type)` is true;
   - every handler parameter other than the `@Command` one is supported by at least one registered
     `HandlerParameterResolver` (none registered → every such handler is an error);
   - every `@Saga` class can be instantiated: the registered `SagaObjectProvider` `supports(type)`, or, with none,
     the class has a no-argument constructor (any visibility, as in the harness);
   - not checked: the instances hosting service handlers and `@AggregateLoader`/`@AggregateSaver` methods. Both
     come lazily from `HandlerInstanceProvider` on first use (spec Assumptions "Handler instances"); its Javadoc
     widens from service handlers to loader and saver hosts. The harness took repositories as objects; engine
     tests register them in a map-backed provider.
3. Any error → `start()` throws `SagaEngineStartupException` holding a `StartupReport(ValidationReport validation,
   List<String> engineErrors)`; the engine stays refused and accepts no event.
4. Otherwise `EventDispatcher.registerSagaType` for every saga type in registration order, then the engine accepts
   events. A `registerSagaType` failure is a startup failure.

New host plug-ins in `core.spi`:

```java
public interface HandlerParameterResolver {
    boolean supports(Executable handler, Parameter parameter);   // startup check
    Object resolve(Executable handler, Parameter parameter);     // inside the unit of work; failures roll it back
}
public interface SagaObjectProvider {
    boolean supports(Class<?> sagaType);                         // startup check
    Object newInstance(Class<?> sagaType);                       // a fresh object per unit of work
}
```

**Rationale**: `validateAll` already reports every model error in one pass; the engine adds only what needs host
plug-ins. `supports` methods make the host checks possible without side effects at startup (no saga object or bean
is created). The Spring starter maps `HandlerParameterResolver` to bean lookup and `SagaObjectProvider` to
prototype beans, so sagas can take injected dependencies.

**Alternatives considered**: Checking parameter resolution and saga instantiation lazily (rejected: FR-035).
Instantiating each saga once at startup to prove it can be (rejected: side effects in constructors, and host
factories may not be ready). A single `Function<Class<?>, Object>` for saga objects (rejected: no side-effect-free
startup check).

## 10. Diagnostics (FR-038)

**Decision**: `java.lang.System.Logger`. The engine logs through `System.getLogger("io.github.gkosharovdev.sagaforge.engine")`
by default; the builder accepts a `System.Logger` to use instead. Hosts redirect everything either way: by
installing a `System.LoggerFinder` (SLF4J 2's `slf4j-jdk-platform-logging` does this) or by passing a logger. Every
message names the event type, saga type and saga id where known, in a fixed `key=value` form (contracts/diagnostics.md).

**Rationale**: JDK-only, no API of our own to maintain, and the JDK already provides the routing hook SLF4J plugs
into, so adopting SLF4J later (G-GOV-01) needs no engine change. Structured outcomes are already in the handling
report for programmatic use.

**Alternatives considered**: A framework diagnostics SPI with typed callbacks (rejected for now: duplicates the
handling report, and Micrometer Observation, the planned metrics route, wraps calls rather than consuming callbacks;
can be added later without breaking anything). `java.util.logging` directly (rejected: routing is per-handler and
global; `System.Logger` is the newer, finder-based facade). Depending on SLF4J now (rejected: FR-037).

## 11. Saga object fields and `SagaInstance.state` (G-ENG-06)

**Decision**: The engine writes `SagaInstance.state` as `Map.of()` on every write, as the harness does. Fields other
than the `@SagaId`, `SagaContext` and `CommandGateway` fields are dependencies (from `SagaObjectProvider` or the
no-argument constructor), not state: never read, never persisted. `SagaInstance.state`'s Javadoc changes from
"serialized fields of the saga class itself" to "reserved; not populated by the engine; values that must survive
between steps belong in `SagaContext`". The component stays, so no record, schema or Postgres change follows.
G-ENG-06 stays open for the decision to use or drop it.

**Rationale**: Persisting arbitrary saga fields would reintroduce the serialization limits of G-003-07 and blur
`SagaContext`'s role. Removing the component is a cross-module shape change this feature does not need.

**Alternatives considered**: Serializing saga fields into `state` (rejected, above). Dropping the component now
(deferred to G-ENG-06's own decision).

## 12. Javadoc corrections (G-ENG-04, G-ENG-01, G-ENG-02)

**Decision**: Corrected in the same change as the engine:

| Type | Today (checked) | Becomes |
|---|---|---|
| `EventDispatcher` | "Implementations must route events to the correct saga instance based on association property correlation"; `dispatch` "may" create an instance; `registerSagaType` enables "event-to-saga routing" | Outbound only: `dispatch` hands an event to the transport for delivery to `EventReceiver`s; routing and correlation are the engine's; `registerSagaType` tells the transport which saga types (and so event types) the engine consumes, e.g. for subscriptions. After-commit delivery goes through `EventHandoff`, whose default calls `dispatch` |
| `SagaLifecycle` | "`EventDispatcher` resolves which instance an incoming event correlates to" | The engine resolves instances through `StorageProvider.findByAssociation` behind `EventReceiver`; the engine persists returned snapshots with the creation write, the unit of work's write and the `FAILED` write |
| `StorageProvider` | "The saga engine persists instance state on every state transition" | Three writes per engine-driven instance (§7); `save(instance, entries)`; `findByIdAndLock`; joining the active transaction; `SagaConcurrencyConflictException` |
| `SagaContextStore` | silent on write order | A record is saved only after its instance's creation write committed (G-ENG-01) |
| `SagaLifecycleEntry`, `SagaLifecycleEventKind` | entries derived by diffing; alphabetical tiebreak; first-save limitation | Produced by the engine, handed over with the write they belong to, ordered by hand-over (§4) |
| `SagaInstance.state` | "serialized fields of the saga class itself" | Reserved, not populated (§11) |
| `DomainEventRecorder`, `EventPublisher`, `CommandGateway` | after-commit delivery "for `EventDispatcher.dispatch`" | through the after-commit handoff (§5) |
| `HandlerInstanceProvider` | service-hosted handlers only | also loader and saver hosts (§9) |

**Rationale**: Principle V; spec Assumptions "Event routing documentation".

**Alternatives considered**: Deferring the Javadoc to a docs task after the engine (rejected: G-ENG-04 has been open
since 003 and the engine is what makes the current text wrong).

## 13. Principle III gate: correctness properties and the stale `.kiro` docs

**Decision**: Tasks add these properties to `.kiro/specs/saga-forge-framework/design.md`, after Property 20, in its
format ("*For any* ..., **Validates: Feature spec `specs/004-saga-engine` FR-...**"):

| # | Property | Validates |
|---|---|---|
| 21 | Loader availability: for any registered `@Aggregate` with an instance-method `@CommandHandler`, `start()` reports an engine error naming the aggregate if and only if no registered `@AggregateLoader` and no registered `AggregateResolver` supports it | FR-035 |
| 22 | Parameter resolvability: for any handler parameter other than its `@Command` parameter, `start()` reports an error naming the handler and parameter if and only if no registered `HandlerParameterResolver` supports it | FR-019, FR-035 |
| 23 | Saga instantiability: for any registered `@Saga` class, `start()` reports an error naming it if and only if neither the `SagaObjectProvider` supports it nor (without one) it has a no-argument constructor | FR-035 |
| 24 | Single-pass startup: for any model, the `StartupReport` contains every `validateAll` error and every error of Properties 21 to 23; an engine whose report has an error accepts zero events | FR-034, FR-036, SC-006 |
| 25 | In-process duplicate start: for any number of start events with the same saga type and association value handled concurrently by one engine with no active instance, exactly one result is `started` and every other is `DUPLICATE_START` | FR-027, SC-012 |
| 26 | Handoff obligations: for any committed unit of work with buffered events, the handoff receives exactly those events, once, in recording order, with a `unitOfWorkId` no other unit of work has, and saga type and id iff saga-scoped; for any rolled-back unit of work it receives nothing (with `IN_TRANSACTION` timing, nothing that survives) | FR-029, FR-030, SC-005, SC-011 |
| 27 | History order: for any engine-driven instance, its persisted lifecycle entries are `CREATED`, then per committed unit of work its `STEP_COMPLETED` entries in completion order followed by `COMPLETED` if it ended, then `FAILED` if it failed; none from a rolled-back unit of work | FR-033, SC-010 |
| 28 | Serialized units of work: for any two units of work of one instance, from one or two engines over shared stores, their step executions never overlap, and a write-time conflict never produces a `FAILED` write | FR-025, FR-026, SC-003 |

`.kiro/specs/saga-context-lifecycle/design.md` Properties 1 to 12 describe an API that does not exist
(`getProperty`, `registerProperty`, `setContext`; G-002-05), so none applies to the engine as written; its
"Engine-Level Error Handling" section (clear the context in a `finally`) is honored in spirit by
`DomainEventScope.runWith` restoring the previous binding. The tasks record that finding next to the new properties
rather than editing that document (AGENTS.md: leave it as-is). G-002-05 stays open: the new properties are appended to
a document whose `SagaInstance` section is stale; correcting that section, or AGENTS.md's "accurate" claim, is the
user's call. The 001/002/003 contract deltas are in [contracts/contract-deltas.md](./contracts/contract-deltas.md).

**Rationale**: FR-039 requires properties for FR-035, FR-027, FR-030 and FR-033; 28 is added because SC-003 is the
feature's main concurrency claim. No existing property (1 to 20) changes: the engine adds no annotation and no
scanner rule.

**Alternatives considered**: Documenting the guarantees only in the 004 contracts (rejected: Principle III names the
`.kiro` design as the property register).

## 14. Test approach and the container suites (G-003-27)

**Decision**:
- **Unit tests only**, JUnit 5 + jqwik + AssertJ (existing versions), in-memory or test SPI implementations in
  `saga-forge-engine/src/test/.../engine/support/`:
  - `InMemoryTransactionBoundary`: thread-bound transactions; test participants (the fixtures' `Snapshottable`
    repositories and services) snapshotted at `begin` and restored at `rollback`, standing in for the host's local
    transaction over client storage; records begin/commit/rollback; failure injection at commit.
  - `TransactionalInMemoryStorageProvider`: writes buffered per transaction and applied at commit (invisible to
    others before, US1-2), `findByIdAndLock` with per-`sagaId` locks owned by the transaction, storage-assigned
    entry numbers, injectable write failures and conflicts. Shared by two engines to model two processes (US3, US4).
  - `InMemorySagaContextStore` and `RecordingEventDispatcher` reused from the core test-jar; never transactional.
  - `CapturingEventHandoff` (both timings), `RecordingLogger` (`System.Logger`), a test transport that only calls
    `EventReceiver` and only receives handoffs (SC-007).
- **Concurrency** (SC-003, SC-012): real threads with a start barrier, repeated at least 100 times
  (`@RepeatedTest(100)` or jqwik `tries = 100`), asserting on step-entry probes and on the stores.
- **Failure positions** (SC-002): a new engine-test fixture, a three-step saga whose handlers are a service, an
  aggregate loaded and saved through a client loader/saver, and an aggregate through an `AggregateResolver`, so one
  unit of work covers every position (each step, each handler, loader, saver, resolver, handler-instance
  provider, parameter resolver, instance write, commit, in-transaction handoff). `ThreeStepSaga` has only
  service handlers, so it cannot cover the loader/saver/resolver positions unchanged.
- **Example** (FR-040): `saga-forge-example` gets a test-scope dependency on `saga-forge-engine` and its test-jar
  and runs `OrderProcessingSaga` end to end. `OrderRepository` is a plain map, so that test seeds the `Order` and
  checks the happy path and a `FAILED` outcome, not aggregate rollback.
- **G-003-27 fixed**: the five container-backed classes in `saga-forge-storage-postgres` (`SchemaTest`,
  `PostgresStorageProviderTest` with its `@Nested` classes, `PostgresSagaContextStoreTest`, `LifecycleHistoryTest`,
  `ConcurrencyControlTest`, all Jupiter) get `@org.junit.jupiter.api.Tag("container")`; nested classes inherit it.
  The module's `pom.xml` configures `maven-surefire-plugin` with `<excludedGroups>${saga.excludedTestGroups}</excludedGroups>`,
  the property defaulting to `container`, and a `container-tests` profile that empties it. A plain `mvn test` is then
  unit-only everywhere; `-Pcontainer-tests` runs the container suites. Plugin configuration is not a dependency
  (SC-008), and the JUnit tag API is already on the test classpath.

**Rationale**: The user's constraints (no containers, no Postgres, no Kafka, no Spring). The test doubles are the
host and database stand-ins the spec's Verification assumption names. The tag removes the hand-maintained `-Dtest`
exclusion list, which this feature would otherwise have to extend with every new Postgres unit test.

**Alternatives considered**: Leaving G-003-27 and extending the `-Dtest` list (rejected: fragile, and a plain root
`mvn test` keeps trying to start a container). A `*IT` naming convention with failsafe (rejected: renames five
classes and adds a plugin binding; the tag is smaller). A Maven profile that excludes by class name (rejected: same
hand-maintained list).

## 15. Verifying the Postgres adaptation without a database (FR-023)

**Decision**: Two kinds of unit test, both run in the default unit suite:

1. **Pure logic**, extracted so it needs no JDBC at all: `LifecycleEntryRows` (entry to history-insert parameters,
   numbering from a given start), `ForwardTransition` (unchanged rule, existing jqwik property kept), and the
   statement text (`FOR NO KEY UPDATE OF i` in the lock query, the history insert's column list).
2. **A statement-recording JDBC fake**, JDK only (`java.lang.reflect.Proxy` over `DataSource`, `Connection`,
   `PreparedStatement`, `ResultSet`): records SQL text, bound parameters, and `setAutoCommit`/`commit`/`rollback`/
   `close` calls per connection, and returns scripted rows. It checks:
   - joined: inside an active `JdbcTransactionBoundary` transaction, `save(instance, entries)`, `findByIdAndLock`,
     `findById` and `findByAssociation` use the bound connection and never commit, roll back, change `autoCommit` or
     close it; history rows are inserted in the given order, numbered from the scripted `MAX + 1`;
   - not joined: 003's own-connection, commit-and-close sequence is unchanged;
   - `findByIdAndLock` without an active transaction throws `IllegalStateException` before any SQL;
   - a `SQLException` in joined mode surfaces as `UncheckedSqlException`, marks the transaction rollback-only and
     leaves the connection open; a forward-transition violation surfaces as `ConcurrentSagaUpdateException`, which
     is a `SagaConcurrencyConflictException`;
   - `JdbcTransactionBoundary`: `begin` sets `autoCommit = false` and binds; `commit`/`rollback` end, restore
     `autoCommit` and close; a second `begin` on the thread throws; `commit` on a rollback-only transaction rolls
     back and throws; `PostgresSagaContextStore` takes its own connection while a transaction is bound.
- The container suites are updated to the new write contract (`LifecycleHistoryTest` hands entries instead of
  relying on derivation) and stay tagged `container`, compiled but not run.

**Residual risk (extends G-003-23)**: none of the following is verified against Postgres: that the statements
parse and run; that `FOR NO KEY UPDATE` blocks a second unit of work and does not block the context store's
foreign-key check (§3, G-ENG-11); that two processes' units of work actually serialize; `MAX + 1` numbering under
the lock; commit/rollback of the joined connection by `JdbcTransactionBoundary`. The fake proves the provider's
call sequence and SQL text, not database semantics.

**Alternatives considered**: An embedded or in-process database (rejected: a new dependency and different locking
semantics). Compile-only for the Postgres module (rejected: the join/no-join branching is exactly what can be
checked without a database).

## 16. Findings while planning

- **FR-006 vs Edge Cases**: FR-006 rejects an event lacking a reacting step's association value with an error and
  no effect; the "Event missing its association value" edge case says "no effect for that saga type", implying other
  saga types still run. This plan follows FR-006 (validate all reacting saga types first, §6).
- **FR-005's outcome list** has no "not handled", which FR-026 requires for a write-time conflict; this plan adds
  `NOT_HANDLED` and also uses it for an event that reaches a still-`CREATED` instance (§3, §6).
- **FR-007 and an active host transaction**: the spec covers calls from a handoff and from a running unit of work,
  not a call made inside the host's own transaction (for example a synchronous transport invoked from a
  `@Transactional` method). The boundary either suspends or rejects (§2); the starter must choose (G-ENG-12).
- **SC-002's positions** need a fixture with loader, saver and resolver handlers; `ThreeStepSaga` has service
  handlers only (§14).
- **G-003-07 meets the canonical sagas**: `OrderProcessingSaga` stores a `BigDecimal` in `SagaContext` and
  `CreateBookingSaga` a `Long`; `JsonCodec` rejects `BigDecimal` and reads a small `Long` back as `Integer`, so both
  would fail on `PostgresSagaContextStore`. Irrelevant to this feature's in-memory tests, but the starter's first
  Postgres run will hit it.
