# Contract: `PostgresMessageStore` (`saga-forge-storage-postgres`)

```java
public PostgresMessageStore(DataSource dataSource, TransactionBoundary transactions)
public PostgresMessageStore(DataSource dataSource, TransactionBoundary transactions, ClassLoader payloadClassLoader)
```

- Implements `core.spi.store.MessageStore` (contracts/core-spi.md) over `saga_message_store` and `saga_message_store_consumer`
  (data-model.md). Uses the connection of the passed `LocalTransaction` (`tx.resource(Connection.class)`), as
  `PostgresStorageProvider` does (004); methods without a transaction open one through `transactions`.
- `append`: one batched `INSERT`; payload via the module's Jackson 3 `JsonCodec` with `payload_type` as the class name;
  unique violation on `message_id` → `DuplicateMessageException`.
- `lease`: single statement — select leasable rows (`status = 'UNCONSUMED' AND next_attempt_at <= now AND (leased_until
  IS NULL OR leased_until < now)` and `NOT EXISTS` an earlier `UNCONSUMED` row with the same `ordering_key`) ordered by
  `position`, `LIMIT limit`, `FOR UPDATE SKIP LOCKED`, then `UPDATE ... SET leased_by, leased_until, attempts =
  attempts + 1 RETURNING *`. Payload decoding happens after the statement; failure → `UnreadableMessageException`.
- Leaseholder writes are `UPDATE ... WHERE message_id = ? AND leased_by = ?`; zero rows → `LeaseLostException`.
- `purgeConsumed`: `DELETE ... WHERE position IN (SELECT position ... WHERE status = 'CONSUMED' AND consumed_at < ? LIMIT ?)`.
- SQL failures → `UncheckedSqlException`, as elsewhere in the module.
- Interop support: `appendDeliveryRecord(LocalTransaction, NewMessage withoutPayload, String externalRef)` (public, used by
  the starter's `ModulithMessageStore`); leases return such rows with `payload` absent and `externalRef` set.

## Schema delivery

- `schema.sql` gains both tables and indexes; `schema-upgrade-007.sql` creates them on a 006 database. The host applies
  them (G-003-25).
