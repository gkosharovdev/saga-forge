# Contract: `SagaMetadata` and `MetadataAccessor`

**Module**: `saga-forge-core` | **Packages**: `core.metadata`, `core.spi` | **Spec**: FR-001 to FR-005, D1, D8

## `SagaMetadata`

```java
public record SagaMetadata(Map<String, String> entries) {
  public static final int MAX_ENTRIES = 32;
  public static final int MAX_KEY_LENGTH = 128;
  public static final int MAX_VALUE_LENGTH = 1024;

  public static SagaMetadata empty();
  public static SagaMetadata of(Map<String, String> entries);                 // strict
  public static SagaMetadata sanitized(Map<String, String> entries,
                                       Consumer<String> droppedKey);        // lenient
  public Optional<String> get(String key);
  public boolean isEmpty();
  public Map<String, String> asMap();
}
```

| Case | `of` | `sanitized` |
|---|---|---|
| `null` map | `IllegalArgumentException` | `empty()` |
| blank or `null` key | `IllegalArgumentException` naming it | dropped, `droppedKey` gets it (`""` for `null`) |
| `null` value | `IllegalArgumentException` naming the key | dropped |
| key longer than 128 or value longer than 1,024 | `IllegalArgumentException` naming the key | dropped |
| more than 32 valid entries | `IllegalArgumentException` | the first 32 in key order kept, the rest dropped |

- Equality is by entries; `toString()` lists keys and values (the application chose them; Saga Forge never logs it,
  diagnostics name keys only, FR-024).
- The canonical constructor applies `of`'s rules, so a deserialised value is always valid.

## `MetadataAccessor`

```java
public interface MetadataAccessor {
  MetadataAccessor NONE = ...;

  Map<String, String> capture();
  Scope restore(SagaMetadata metadata);

  interface Scope extends AutoCloseable {
    Scope NOOP = () -> {};
    @Override void close();
  }
}
```

`capture()` returns a plain map, not `SagaMetadata`, so an accessor never has to apply the bounds itself: the caller
passes it to `SagaMetadata.sanitized(map, droppedKey)`, which drops invalid entries by key (implementation decision,
T003).

## `CommandInvocation.metadata()` (added during implementation, memory.md G-019-09)

`default SagaMetadata metadata()` returns empty; the runtime returns the metadata the handler receives as its
`SagaMetadata` parameter. The fixture reads it to fill `RecordedCommand.metadata()`.

**Implemented by host integrations** (the starter's `MdcMetadataAccessor`, a hand-assembling host, the fixture's test
doubles); called only by the runtime, the transport and the fixture.

| Method | Called | Contract |
|---|---|---|
| `capture()` | On the thread that produces a message or hands an event to the runtime outside a delivery | Returns the thread's metadata, never `null`; may return entries over the bounds (the caller sanitises). A thrown exception is caught by the caller: empty metadata, diagnostic `saga.metadata.capture.failed` |
| `restore(m)` | At the start of each unit of work and each relay delivery (see [propagation.md](propagation.md)) | Makes `m` current; returns a scope, never `null`. Must tolerate nesting. A thrown exception: diagnostic `saga.metadata.restore.failed`, the work runs without it |
| `Scope.close()` | In `finally`, on the same thread | Puts back exactly what the thread held before the matching `restore`. A thrown exception: same diagnostic, swallowed |

`NONE.capture()` returns an empty map; `NONE.restore(m)` returns a scope whose `close()` does nothing.

## Tests that exercise the abstraction before use (AGENTS.md)

- `SagaMetadataTest`: every row of the table above.
- `MetadataAccessorContractTest` (core test-jar): an abstract contract any accessor runs (capture after restore returns
  the restored keys; close puts back the previous state; nested scopes unwind in order); run against a
  `ThreadLocalMetadataAccessor` test double in core and against `MdcMetadataAccessor` in the starter.
