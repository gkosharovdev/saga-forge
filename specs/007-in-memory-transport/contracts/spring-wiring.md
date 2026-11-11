# Contract: Spring wiring for the transport (`saga-forge-spring-boot-starter`)

Scope (FR-029): only what the in-memory transport needs from a Spring host. Classpath detection of storage/transport and
engine assembly stay in the later starter feature (G-007-02); the host still builds `SagaEngine` and passes the pieces.

## `SpringTransactionBoundary implements TransactionBoundary`

- `begin()`: starts a transaction through `PlatformTransactionManager` with `PROPAGATION_REQUIRED`; if a Spring transaction
  is already active, joins it (the engine's 004 G-ENG-12 rule: this boundary joins rather than suspends — documented).
- `current()`: the transaction started by this boundary on this thread, if any; `resource(Connection.class)` returns the
  connection bound by `DataSourceUtils` for the configured `DataSource`.

## `InMemoryTransportConfiguration` (`@AutoConfiguration`, conditional on `InMemoryTransport` and a `MessageStore` bean or a `DataSource`)

- Beans: `SpringTransactionBoundary` (if none), `PostgresMessageStore` (if none and Postgres module present),
  `InMemoryTransport` with settings from `saga-forge.transport.*` (`workers`, `max-attempts`, `backoff.initial`,
  `backoff.max`, `lease-duration`, `poll-interval`, `retention`, ...) and Boot's `ThreadPoolTaskExecutor` builder
  (`saga-forge-transport-` thread prefix), class loader = application class loader.
- `TransportLifecycle` (`SmartLifecycle`, phase after the host's engine lifecycle): `start()` calls
  `transport.start(engine, engine.commandReceiver())` using the `SagaEngine` bean; `stop()` calls
  `transport.stop(spring.lifecycle.timeout-per-shutdown-phase)`.
- `ApplicationEventBridge` (on by default when `saga-forge.transport.publish-application-events` is not `false`): registers
  `transport.onDelivered(...)` and re-publishes each delivered event's payload through `ApplicationEventPublisher`
  outside any transaction (FR-027).

## Spring Modulith interop (`ModulithInteropConfiguration`, conditional on an `EventPublicationRegistry` bean and `saga-forge.transport.modulith-interop != false`)

- Replaces the `MessageStore` bean with `ModulithMessageStore(registry, postgresMessageStore)`:
  - `append(tx, entries)`: for each entry, `registry.store(new SagaForgeEnvelope(entry), Stream.of(TARGET))` and
    `postgresMessageStore.appendDeliveryRecord(tx, entryWithoutPayload, publicationId)` — both in the active Spring
    transaction (`TARGET = PublicationTargetIdentifier.of("io.github.gkosharovdev.sagaforge.transport")`).
  - `lease(...)`: leases delivery records through `postgresMessageStore`, then resolves payloads from
    `registry.findIncompletePublications()` filtered by `TARGET` and matched by envelope message id; a missing
    publication → `UnreadableMessageException` (set aside with reason `PUBLICATION_MISSING`).
  - `markConsumed`: delivery record consumed, then `registry.markCompleted(envelope, TARGET)` in the same transaction.
  - `setAside`: delivery record set aside, then `registry.markFailed(envelope, TARGET)`.
  - `purgeConsumed`: delivery records purged; `registry.deleteCompletedPublicationsOlderThan(retention)`.
  - Everything else delegates to `postgresMessageStore`.
- `SagaForgeEnvelope`: record `(String messageId, MessageKind kind, String payloadType, Object payload, ...)` serialized by
  the registry's `EventSerializer` (Boot's Jackson mapper). Must round-trip deterministically (risk R1, spike first).
- Startup check: if `spring.modulith.events.republish-outstanding-events-on-restart=true`, log INFO that Saga Forge
  entries are excluded (no listener for `TARGET`).

## Dependencies

- `spring-boot-autoconfigure` 4.1.2 (existing), `spring-jdbc`, `spring-tx` (Boot-managed).
- `saga-forge-transport-inmemory`, `saga-forge-storage-postgres` — optional.
- `org.springframework.modulith:spring-modulith-events-core` 2.1.x — optional.
