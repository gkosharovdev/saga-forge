# Contract: Durable `SagaContext` value rule (FR-019)

A value is **durable** if it is, recursively:

- `String`, `Boolean`, `Integer`, `Long`;
- a finite `Double`;
- a `Map` whose keys are all `String` and whose values are durable;
- a `List` whose elements are durable.

`null` is already rejected by `SagaContext` itself.

## Fixture

With `checkContextValues(true)` (default), the fixture's `SagaContextStore.save` throws
`UnsupportedContextValueException` for the first non-durable value, naming saga type, saga id, key path
(`orderAmount`, `items[2].price`) and value type. The `given`/`when` call that triggered it fails with
`SagaExpectationError`:

```text
OrderProcessingSaga[orderId=o-1] (saga-1), when OrderCreatedEvent[...]:
  step handleOrderCreated stored SagaContext value 'orderAmount' of type java.math.BigDecimal,
  which durable storage cannot persist (supported: String, Boolean, Integer, Long, finite Double, Map with String keys, List).
  Store it as a String (for example amount.toPlainString()), or build the fixture with checkContextValues(false).
```

## Consistency with storage

`saga-forge-storage-postgres` adds `DurableContextValueContractTest` (container, test scope on `saga-forge-test`): every
value in a shared sample set that the fixture accepts is saved and read back equal by `PostgresSagaContextStore`, and
every value it rejects is rejected by Postgres. Future storage modules add the same test.
