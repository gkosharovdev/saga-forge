# Contract: `TransactionBoundary` and `LocalTransaction`

**Package**: `io.github.gkosharovdev.sagaforge.core.spi` | **Kind**: interfaces, implemented by host integrations
(the Spring starter later; `JdbcTransactionBoundary` in `saga-forge-storage-postgres`; test doubles) |
**Consumers**: the engine (begin, commit, rollback, mark rollback-only); storage providers and an outbox handoff
(`current()`, `resource(...)`). Spec: FR-020 to FR-024, FR-032; research §2.

Both are bound to the calling thread. All null arguments raise `IllegalArgumentException`.

## `TransactionBoundary`

### `LocalTransaction begin()`

- **Effect**: starts a new top-level local transaction on the calling thread and returns it; `current()` returns it
  until it is committed or rolled back.
- **Never joins.** If a transaction is already active on the thread, the implementation either suspends it until the
  new one ends (JTA `TransactionManager.suspend`/`resume`, Spring `PROPAGATION_REQUIRES_NEW`) or throws
  `IllegalStateException`. Which one is documented by the implementation; test doubles and `JdbcTransactionBoundary`
  throw.
- **Failure**: unchecked exceptions from the underlying resource (for example no connection available) propagate.

### `Optional<LocalTransaction> current()` / `default boolean isActive()`

The transaction begun through this boundary and active on the calling thread, if any. `isActive()` is
`current().isPresent()`.

## `LocalTransaction`

| Operation | Effect | Failure |
|---|---|---|
| `void commit()` | Commits; afterwards `current()` is empty | Unchecked exception if the commit fails (the transaction is then rolled back). On a rollback-only transaction: rolls back, then throws `IllegalStateException`; the engine never does this |
| `void rollback()` | Rolls back; afterwards `current()` is empty | Unchecked exception if the rollback fails; the transaction is ended anyway |
| `void setRollbackOnly()` | Marks the transaction so it can only roll back | `IllegalStateException` if already ended |
| `boolean isRollbackOnly()` | Whether it is marked | |
| `<R> Optional<R> resource(Class<R> type)` | The transaction's resource of that type, e.g. `java.sql.Connection` for a JDBC transaction, or empty if it has none of that type. Callers use it and never commit, roll back, reconfigure or close it | `IllegalStateException` if already ended |

`commit`, `rollback`, `setRollbackOnly` on an ended transaction throw `IllegalStateException`. A local transaction
has at most one resource of a given type; there is no nesting, no savepoint and no two-phase commit.

## Mapping (no dependency on either API)

| Contract | JTA (`jakarta.transaction`) | Spring (`PlatformTransactionManager`) |
|---|---|---|
| `begin()` | `TransactionManager.begin()`; an active transaction is `suspend()`ed first and `resume()`d after, or rejected | `getTransaction(def)`, `PROPAGATION_REQUIRES_NEW`; keep the `TransactionStatus` |
| `commit()` / `rollback()` | `commit()` / `rollback()` | `commit(status)` / `rollback(status)` |
| `setRollbackOnly()` / `isRollbackOnly()` | `setRollbackOnly()` / `getStatus() == STATUS_MARKED_ROLLBACK` | `status.setRollbackOnly()` / `status.isRollbackOnly()` |
| `current()`, `isActive()` | `getStatus() != STATUS_NO_TRANSACTION` | `TransactionSynchronizationManager.isActualTransactionActive()` |
| `resource(Connection.class)` | connection of the JTA-enlisted `DataSource`, or `TransactionSynchronizationRegistry.getResource(key)` | `DataSourceUtils.getConnection(dataSource)` |

Every operation corresponds to one both APIs offer, and nothing here requires nesting or distribution (FR-021).

## How the engine uses it

| Write | Transaction |
|---|---|
| Creation write | its own: `begin`, `save(instance, [CREATED])`, `commit` |
| Saga-scoped unit of work | one, from before `findByIdAndLock` to after the instance write and any `IN_TRANSACTION` handoff |
| Non-saga unit of work (`send` outside a unit of work) | one, around the handler |
| `FAILED` write | its own, after the unit of work's `rollback` |

When a handler, loader, saver, resolver or provider throws inside `CommandGateway.send`, the engine calls
`setRollbackOnly()` before the exception leaves `send` (002 FR-012). Before committing, the engine checks
`isRollbackOnly()` and rolls back instead.

## Joining (FR-022)

- **Storage providers** built with the host's boundary use `current().flatMap(tx -> tx.resource(T.class))` for
  every call while a transaction is active (reads included), and their own transaction otherwise. A storage call
  inside an active transaction that has no resource of the expected type fails with `IllegalStateException` naming
  the type.
- **Aggregate access** joins by running on the unit-of-work thread while the transaction is active: host-managed
  repositories join through the host's own binding; plain-JDBC client code reads `resource(Connection.class)`.
- **`SagaContextStore`** is never built with the boundary and never joins.
- **An outbox handoff** (`IN_TRANSACTION`) writes through `current()` the same way.

## `JdbcTransactionBoundary` (`saga-forge-storage-postgres`, public)

- `JdbcTransactionBoundary(DataSource dataSource)`.
- `begin()`: `IllegalStateException` if a transaction is bound on this thread; takes a connection, remembers and
  sets `autoCommit = false`, binds it.
- `commit()`/`rollback()`: commit or roll back the connection, restore `autoCommit`, close it, unbind; a failure is
  wrapped in `UncheckedSqlException` after unbinding and closing.
- `resource(Connection.class)` returns the bound connection; any other type → empty.
- The pool must allow at least two connections per concurrent unit of work: the transaction's, and
  `PostgresSagaContextStore`'s own.
