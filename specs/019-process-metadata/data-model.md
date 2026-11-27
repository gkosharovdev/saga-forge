# Data Model: Process Metadata Propagation

**Feature**: `specs/019-process-metadata` | **Date**: 2026-11-27 | **Research**: [research.md](research.md)

Every changed record keeps its existing public constructors, which pass `SagaMetadata.empty()` (the pattern used
since 005 for additive components).

## New types (saga-forge-core)

### `core.metadata.SagaMetadata` (public record)

| Component | Type | Rule |
|---|---|---|
| `entries` | `Map<String, String>` | Unmodifiable, key-ordered copy; keys non-blank and at most `MAX_KEY_LENGTH`; values non-null and at most `MAX_VALUE_LENGTH`; at most `MAX_ENTRIES` entries |

- Factories: `empty()`; `of(Map)` (strict, throws `IllegalArgumentException` naming the offending key);
  `sanitized(Map, Consumer<String> droppedKey)` (drops offending entries, calls `droppedKey` once per dropped key;
  entries beyond `MAX_ENTRIES` are dropped in key order).
- Queries: `get(String key)` → `Optional<String>`, `isEmpty()`, `asMap()`.
- Constants: `MAX_ENTRIES = 32`, `MAX_KEY_LENGTH = 128`, `MAX_VALUE_LENGTH = 1024`.
- Never interpreted by Saga Forge; never routing, association or deduplication input.

### `core.spi.MetadataAccessor` (public interface, host SPI)

| Member | Meaning |
|---|---|
| `Map<String, String> capture()` | The current thread's entries, unsanitised; never `null` (the caller applies `SagaMetadata.sanitized`) |
| `Scope restore(SagaMetadata metadata)` | Makes `metadata` current on this thread until the scope closes |
| `interface Scope extends AutoCloseable { void close(); }` | Puts back what the thread held before `restore` |
| `MetadataAccessor NONE` | Captures an empty map, restores nothing |

Thread confinement: a scope is closed on the thread that opened it. Implementations must tolerate nested scopes.

### `core.spi.EventDelivery` (public record)

| Component | Type | Rule |
|---|---|---|
| `event` | `Object` | Non-null domain event or outcome event |
| `sourceSagaId` | `String` | `null` or non-blank (014's source instance) |
| `metadata` | `SagaMetadata` | Non-null; `null` becomes `empty()` |

## Changed types (saga-forge-core)

| Type | Change |
|---|---|
| `EventReceiver` | New default `HandlingReport receive(EventDelivery delivery)`: calls `receive(event, sourceSagaId)` when a source is set, else `receive(event)`; metadata ignored by the default |
| `SagaInstance` | New component `SagaMetadata metadata` (last); `withMetadata(SagaMetadata)`; fixed after the creation write (runtime rule, not a record rule) |
| `SagaLifecycleEntry` | New component `SagaMetadata metadata` (last): the triggering message's for `STEP_COMPLETED`, `ATTEMPT_FAILED`, `FAILED` and compensation entries caused by a delivery; the instance's for operator, recovery and deadline-free engine entries |
| `NewMessage`, `StoredMessage` | New component `SagaMetadata metadata` next to `traceContext` |
| `OutgoingMessages` | New component `SagaMetadata metadata`: the metadata every message of the batch carries |
| `OutboxCommand` | New component `SagaMetadata metadata`: the command message's metadata |
| `CompensationTrigger`, `OutcomeTrigger`, outcome events | Unchanged (D6) |

## Scanner rules and property

| Id | Where | Rule |
|---|---|---|
| R32 | `validate`, `validateAll` | A `@Saga` class declares at most one field whose declared type is `SagaMetadata`: `"{Saga} declares {n} SagaMetadata fields ({names}); declare at most one"` |
| R33 | `validateAll` (host rule) | A `@CommandHandler` method or constructor declares at most one `SagaMetadata` parameter: `"{Handler} declares {n} SagaMetadata parameters; declare at most one"` |

**Property 50 (metadata visibility)**: for any model and any delivery, (a) R32 and R33 hold exactly when the scanner
reports no error from them; (b) every saga object a unit of work creates for an instance has its `SagaMetadata` field,
if declared, equal to the instance's metadata; (c) a `SagaMetadata` handler parameter equals the metadata of the unit of
work that handles the command (the sending instance's for an in-transaction command, the command message's for an
outbox command, the capture for a command sent outside any saga); (d) every message in a unit of work's
`OutgoingMessages` carries that unit of work's metadata. Added to `.kiro/specs/saga-forge-framework/design.md`.

## Runtime state (saga-forge-runtime, package-private)

| Element | Change |
|---|---|
| `SagaRuntime.Builder` | `metadataAccessor(MetadataAccessor)`, default `NONE` |
| `EngineSettings` | Holds the accessor |
| `EventRouter` | Main entry `receive(EventDelivery)`; the old entries capture first; queue entries keep their delivery's metadata; the creation write sets `SagaInstance.metadata` |
| `UnitOfWork` | Restores its metadata around the whole run (R5); `OutgoingMessages.metadata` = its metadata |
| `WorkContext` / `Injection` | Carry the metadata; `newSagaObject()` injects the `SagaMetadata` field |
| `SagaModel` | Finds the optional `SagaMetadata` field |
| `EngineCommandGateway` | Supplies a `SagaMetadata` handler parameter; a send outside any unit of work captures |
| `EngineSagaLifecycle` | Writes entry metadata as in the table above |
| `Diagnostics` | `saga.metadata.capture.failed`, `saga.metadata.restore.failed`, `saga.metadata.entry.dropped` |

## Transport state (saga-forge-transport-inmemory)

| Element | Change |
|---|---|
| `InMemoryTransport.Builder` | `metadataAccessor(MetadataAccessor)`, default `NONE` |
| `LogAppender` | Batch entries take `batch.metadata()`; `published(...)` takes the capture |
| `Relay` | Restores `message.metadata()` around each delivery; calls `receive(EventDelivery)`; builds `OutboxCommand` with the metadata |
| `InMemoryMessageStore` (test jar), `MessageStoreContract` | Keep and assert metadata round trips |

## Storage: Postgres (`V6__process_metadata`)

| Table | Column |
|---|---|
| `saga_instance` | `metadata JSONB NOT NULL DEFAULT '{}'::jsonb` |
| `saga_lifecycle_history` | `metadata JSONB NOT NULL DEFAULT '{}'::jsonb` |
| `saga_message_store` | `metadata JSONB NOT NULL DEFAULT '{}'::jsonb` |

Encoded by `JsonCodec` as a JSON object of strings; `{}` and `NULL` decode to `SagaMetadata.empty()`. Also in
`schema.sql`, `schema-upgrade-019.sql`, `schema-levels/V6.json`, `PostgresSchema.versions()`.

## Starter

| Element | Change |
|---|---|
| `SagaForgeProperties.Metadata` | `mdcKeys: List<String>` (default empty) |
| `SagaForgeMetadataConfiguration` | `MdcMetadataAccessor` bean when SLF4J is present and keys are configured; `@ConditionalOnMissingBean(MetadataAccessor.class)` |
| Runtime, transport, inbound listener wiring | Receive the accessor bean (or `NONE`) |
| `SagasEndpoint` | Instance and history views gain `metadata` |
| `sagaforge` endpoint | `metadata: {accessor: <class or "none">, mdcKeys: [...]}` |

## Fixture (saga-forge-test)

`SagaFixture.when(Object, SagaMetadata)`, `Given.given(SagaMetadata, Object...)`, `Builder.metadataAccessor`,
`InstanceView.metadata()`, `RecordedCommand.metadata()`, `Then.expectMetadata(SagaMetadata)`; package-private
plug-ins keep metadata on stored messages and instances.
