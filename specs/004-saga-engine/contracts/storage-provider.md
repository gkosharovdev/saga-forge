# Contract delta: `StorageProvider` write and lock operations, `SagaLifecycleEntry`

**Package**: `io.github.gkosharovdev.sagaforge.core.spi` | **Kind**: SPI change (Principle III gate) |
**Implemented by**: storage modules (Postgres now), the test doubles | **Consumer**: the engine. Spec: FR-022 to
FR-027, FR-033; research §3, §4.

Unchanged: `findById`, `findByAssociation`, `delete` and their null contracts. Every operation joins the active
`TransactionBoundary` transaction when the provider was built with one and a transaction is active
([transaction-boundary.md](./transaction-boundary.md)); otherwise it runs in its own transaction as before.

## `void save(SagaInstance instance, List<SagaLifecycleEntry> newEntries)` *(new, abstract)*

- **Pre-conditions**: both non-null; `newEntries` has no `null` element; every entry's `sagaId` equals
  `instance.sagaId()`.
- **Effect**: creates or updates the instance record and its associations, and appends `newEntries` to the
  instance's lifecycle history after every entry already recorded, in list order, all in one transaction. The
  provider assigns each appended entry its position (Postgres: `sequence_number` = current `MAX + 1`, consecutive).
  The provider derives nothing: it records exactly the entries it is given.
- **Failure**: `IllegalArgumentException` for the pre-conditions, before anything is written;
  `SagaConcurrencyConflictException` (or a subclass) if the write detects a concurrent change, nothing written;
  other storage failures as unchecked exceptions, nothing written.

## `default void save(SagaInstance instance)` *(changed: now `save(instance, List.of())`)*

Writes the instance and records no history. 003's diffing derivation is gone; a caller that wants history passes the
entries.

## `Optional<SagaInstance> findByIdAndLock(String sagaId)` *(new, abstract)*

- **Pre-conditions**: `sagaId` non-null; a transaction the provider has joined is active on the calling thread.
- **Effect**: reads the instance and holds an exclusive lock on it until that transaction commits or rolls back.
  The same call for the same `sagaId` in any other transaction, in this or another process, blocks until then and
  then reads the committed state. Locks on different `sagaId`s are independent. Locking a missing instance returns
  empty and locks nothing (a missing instance cannot be raced: the engine creates it in its own committed write).
  The lock must not block the `SagaContextStore`'s own writes for the same `sagaId` (Postgres: `FOR NO KEY UPDATE`,
  research §3).
- **Failure**: `IllegalArgumentException` for `null`; `IllegalStateException` if no joined transaction is active,
  before touching the store; a lock wait aborted by the store (timeout, deadlock victim) as an unchecked exception.

## `SagaConcurrencyConflictException` *(new, unchecked)*

`SagaConcurrencyConflictException(String sagaId, String message)`, `String sagaId()`. Thrown by a write that detects
that the stored instance changed concurrently. The engine treats it as "not handled", never as a saga failure
(FR-026). Postgres's `ConcurrentSagaUpdateException` extends it.

## `SagaLifecycleEntry` *(changed record)*

Components after the change: `sagaId`, `kind`, `stepName`, `touchedAggregates`, `outcomeStep`, `failure`,
`occurredAt`. **Removed**: `sequenceNumber` (storage assigns positions). Present-iff rules and null checks are
unchanged. Javadoc: produced by the engine and handed to storage with the instance write it belongs to (creation
write, unit-of-work write, `FAILED` write); persisted order is hand-over order; no query API (G-003-24).

`SagaLifecycleEventKind` values are unchanged: `CREATED` is the creation write, `STEP_COMPLETED` one completed
step, `COMPLETED` an `@EndSaga` outcome, `FAILED` the `FAILED` write. No kind for `RUNNING` (G-003-18).

## Postgres adaptation (`saga-forge-storage-postgres`)

| Operation | Statements | Joined (transaction active) | Not joined |
|---|---|---|---|
| `findByIdAndLock` | `SELECT_INSTANCE ... WHERE i.saga_id = ? FOR NO KEY UPDATE OF i` | bound connection | `IllegalStateException` |
| `save(instance, entries)` | `LOCK_PREVIOUS` (`FOR NO KEY UPDATE OF i`), forward-transition check, `NEXT_SEQUENCE_NUMBER`, upsert (`RETURNING (xmax = 0)`), association replace, history batch insert of `entries` | bound connection; no `commit`/`rollback`/`setAutoCommit`/`close`; a `SQLException` → `setRollbackOnly()`, `UncheckedSqlException` | own connection and transaction, as 003 |
| `findById`, `findByAssociation`, `delete` | as 003 | bound connection | own connection, as 003 |

- `PostgresStorageProvider(DataSource)` keeps 003's behavior and cannot lock (no boundary);
  `PostgresStorageProvider(DataSource, TransactionBoundary)` joins. The host binds the connection of the same
  `DataSource`.
- The forward-transition check (`ForwardTransition.requireForwardTransition`, ex-`LifecycleHistoryDerivation`) and its
  known gaps (G-003-02, G-003-13, G-003-19, G-003-20) are unchanged. The first-save race check (G-003-03, G-003-22)
  stays; the engine's creation write always uses a fresh `sagaId`.
- `PostgresSagaContextStore` keeps its own connection and transaction.
- `schema.sql` is unchanged.
