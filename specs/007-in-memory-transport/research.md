# Research: In-Memory Transport with a Durable Message Log

**Feature**: `specs/007-in-memory-transport` | **Date**: 2026-11-11

Each section records a decision, why, and what else was considered. Section numbers are cited from `plan.md`,
`data-model.md` and `contracts/`.

## §1 Where the store sits: a transactional outbox behind the 004 outbox

- **Decision**: The transport supplies the engine's `Outbox` with `Timing.IN_TRANSACTION`. `store(batch)` appends
  one store entry per event (and per outbox command, §3) through `MessageStore.append` in the unit of work's transaction.
  A relay inside the transport (§5) delivers committed entries. `EventDispatcher.dispatch` (the path for
  `EventPublisher.publish` outside any unit of work) appends in a transaction of its own, then signals the relay.
- **Rationale**: this is the shape G-ENG-07 and the `Outbox` Javadoc already anticipate; FR-001/FR-002 follow
  directly, and the entry exists if and only if the unit of work committed.
- **Alternatives**: `AFTER_COMMIT` outbox writing to the store after commit — leaves the G-ENG-07 window open. Writing
  the store from storage (`StorageProvider.save`) — couples saga storage to the transport.

## §2 Waking the relay after commit: `Outbox.committed`

- **Decision**: Add a default method `Outbox.committed(OutgoingMessages batch)` (no-op by default). For an
  `IN_TRANSACTION` outbox the engine calls it after the commit returned, with the lock released and no engine
  transaction active; a thrown exception is logged and changes nothing. The transport uses it to signal the relay. The
  relay also polls (default every 500 ms) so a missed signal, a backoff that became due, or a commit by another path is
  still picked up.
- **Rationale**: `LocalTransaction` has no after-commit callback, and the in-transaction outbox cannot know whether the
  commit succeeded. Signalling keeps SC-003 (100 ms p99) without tight polling; polling alone would need a ≤ 50 ms
  interval and constant queries.
- **Alternatives**: `LocalTransaction.afterCommit(Runnable)` — touches every `TransactionBoundary` implementation
  (JDBC, Spring, test doubles); the outbox hook is one engine call site. Poll only — misses SC-003 or burns queries.
- **Spec impact**: this is an additive SPI change, so FR-025 is reworded from "existing SPI contracts only" to "SPI
  contracts in `saga-forge-core`, extended additively" (applied to `spec.md`).

## §3 Outbox commands (FR-010 to FR-013)

- **Decision**:
  - Opt-in attribute on the command type: `@Command(delivery = CommandDelivery.OUTBOX)`; the default
    `CommandDelivery.IN_UNIT_OF_WORK` is today's behaviour. On the command type, not the handler, because the sender
    must know at `send` time without resolving a handler and because "one handler per command type" (Property 15) makes
    the two equivalent.
  - `EngineCommandGateway.send` of a `OUTBOX` command inside a unit of work assigns the `CommandId` exactly as today
    (005: saga, step, type, ordinal), skips it if the step record already lists it handled, and otherwise buffers it in
    the unit of work instead of running the handler. Outside a unit of work it opens the non-saga unit of work and
    buffers it there (FR-010a). Nothing runs before commit.
  - The buffered commands travel with the events in the outgoing batch: `OutgoingMessages` gains
    `List<OutgoingCommand> commands` (each a `CommandId` plus the command); a batch may now have no events if it has
    commands. The existing 4-argument constructor stays (no commands).
  - The engine implements a new core SPI `CommandReceiver`: `receive(OutboxCommand delivery)` runs the single handler
    in a non-saga unit of work with the delivery's `CommandId` bound (so a `CommandId` parameter gets the original
    identity, FR-012) and calls `delivery.inTransaction()` before the commit. The transport passes a callback that
    marks the entry consumed in that same transaction.
  - A sent `OUTBOX` command counts as "handled" for the sending step's `StepRecord.handledCommands` when the step's unit
    of work commits (it has been durably stored in the outbox), so a retried or redelivered step does not log it twice.
- **Rationale**: the consumed mark inside the handler's transaction makes a outbox command's effects and its
  consumption atomic in the shared datastore: a redelivery after a crash either finds it consumed or finds the
  handler's effects rolled back. Events cannot get the same treatment because one event may open several units of work
  (one per instance); they rely on the engine's `DUPLICATE` detection instead (§6).
- **Alternatives**: opt-in on `@CommandHandler` — the sender would have to resolve the handler first and two
  declarations could disagree. A separate `CommandOutbox` SPI — a second call site with the same timing and
  transaction; one batch is simpler for the outbox key `(unitOfWorkId, index)`.

## §4 Scanner rule R23 and Property 35

- **Decision**: R23 (cross-class, `validateAll`): a `@Command(delivery = OUTBOX)` type must have exactly one
  `@CommandHandler` in the validated model. Today a missing handler surfaces only when `send` runs; for a outbox command
  it would surface only at delivery, after the sender committed, so it must be caught at startup. Property 35
  (runtime): a `OUTBOX` command sent inside a unit of work is never handled before that unit of work commits, is never
  handled if it rolls back, and its handler receives the `CommandId` assigned at send time. Both are added to
  `.kiro/specs/saga-forge-framework/design.md` before implementation (Constitution Principle III).
- **Alternatives**: no rule — leaves a silent set-aside at runtime for a model error.

## §5 Relay: leasing, ordering and workers

- **Decision**:
  - Every entry gets a `position` (monotonic sequence assigned at append) and an `orderingKey`: for an event, the
    `@DomainEvent(aggregate = X)` type plus the event's `@AggregateIdentifier` value (Properties 16–17 guarantee both);
    for a command, its `@AggregateIdentifier` value if it has one, otherwise the sending saga id, otherwise none.
  - The relay leases due entries with a lease (`leasedBy`, `leasedUntil`): `UNCONSUMED`, not leased or lease
    expired, `nextAttemptAt <= now`, and **no earlier `UNCONSUMED` entry with the same ordering key**. Set-aside entries
    do not block (FR-016). So at most one entry per key is in flight, and per-key order holds.
  - Leased entries go to N worker threads (default 4, host-supplied executor allowed); entries without a key run in
    any worker.
  - Lease default 5 minutes, renewed while a delivery runs longer than half of it.
- **Rationale**: FR-007 asks for order per saga instance, but the transport cannot know which instance an event
  reaches until the engine routes it. The aggregate is the closest key the model guarantees, and it equals the
  instance whenever `associationProperty` is the aggregate identifier, as in the canonical example. The gap for
  sagas correlated by another property is recorded as G-007-04.
- **Ordering caveat**: positions are assigned at append, not at commit, so two concurrent units of work of the same key
  can commit out of position order. Order holds within a unit of work and between units of work that are serialized
  (the same saga instance holds its lock); recorded in G-007-04.
- **Alternatives**: one global delivery thread — trivially ordered but one slow or retrying message stalls everything
  (violates FR-016). `FOR UPDATE SKIP LOCKED` held during delivery — the delivery opens its own transactions, so the
  row lock cannot span it.

## §6 Consumption and redelivery

- **Decision**: an event entry is marked consumed in a short transaction after `EventReceiver.receive` returned a
  `HandlingReport` (any outcome, including `FAILED`, `COMPENSATED`, `REQUIRES_INTERVENTION`, `DUPLICATE`,
  `DUPLICATE_START`, ignored: FR-017). A outbox command entry is marked consumed inside the handler's transaction (§3).
  A crash between an event's handling commit and its consumed mark redelivers it, and the engine reports the step as
  `DUPLICATE` (005).
- **Failures**: an exception from the receiver (including `EventHandlingException`) increments `attempts`, stores the
  failure (class and message, truncated to 4,000 characters), releases the lease and sets `nextAttemptAt` with
  exponential backoff (1 s doubling, capped at 1 min). After `maxAttempts` (default 10) the entry becomes `SET_ASIDE`.
  An entry whose payload cannot be decoded (class missing, decode error) becomes `SET_ASIDE` at once with that reason.
- **Restart**: no special path. At `start()` the relay releases leases held by this transport's previous run (same
  consumer id is not assumed; expired leases suffice) and leases as usual, so unconsumed entries are delivered in
  per-key position order (FR-018) before or alongside new work; "before accepting new work" is met because new entries
  have higher positions within their key.
- **Alternatives**: count the attempt before delivery (like Spring Modulith's `completionAttempts`) — chosen as well:
  `attempts` is incremented at lease, so a crash mid-delivery is counted (avoids an endless crash loop; contrast
  G-005-05).

## §7 Payload encoding

- **Decision**: the store stores the payload as JSON plus the fully qualified class name, encoded by the storage module
  (Postgres: the existing Jackson 3 `JsonCodec`). `saga-forge-core` carries only `Object`. The class is loaded with a
  configurable `ClassLoader` (default: the thread context class loader at transport start) for hosts with restart class
  loaders (Spring DevTools, Quarkus dev mode).
- **Alternatives**: Java serialization — brittle and unsafe to read back. Encoding in the transport — would pull Jackson
  into a module that otherwise needs none.

## §8 Postgres implementation

- **Decision**: `PostgresMessageStore` in `saga-forge-storage-postgres`, joining the engine's transaction through the
  same `TransactionBoundary` as `PostgresStorageProvider` (004). One table `saga_message_store` (data-model.md) with a
  `bigserial` position, a partial index on `(ordering_key, position) WHERE status = 'UNCONSUMED'` for the head-of-key
  check, and an index on `(status, next_attempt_at)`. Shipped in `schema.sql`; existing databases apply
  `schema-upgrade-007.sql`. A consumer heartbeat table `saga_message_store_consumer` backs FR-021.
- **Interop mode** reuses the same table as the side record with `payload` null and `external_ref` holding the Spring
  Modulith publication identity (§10), so there is one schema and one bookkeeping implementation.

## §9 Retention and shutdown

- **Decision**: a sweeper runs every hour (configurable) and deletes `CONSUMED` entries whose `consumedAt` is older than
  the retention (default 7 days, FR-019) in batches of 1,000. `stop(timeout)` (default 30 s) stops leasing, waits for
  running deliveries, then releases the leases of the rest so the next start delivers them (FR-020).

## §10 Spring wiring and Spring Modulith interop (FR-026 to FR-029)

- **Versions**: the starter is on Spring Boot 4.1.2 (Spring Framework 7.0). Spring Modulith 2.1.x (2.1.1 checked) is
  its matching line; its `EventPublicationRegistry` (`org.springframework.modulith.events.core`) offers `store(event,
  Stream<PublicationTargetIdentifier>)`, `findIncompletePublications()`, `markProcessing`, `markCompleted`,
  `markFailed` and `deleteCompletedPublicationsOlderThan`, with JDBC, JPA and MongoDB repositories and a Jackson
  `EventSerializer` using Boot's mapper. Since 2.0 publications have a status (`PUBLISHED`, `PROCESSING`, `COMPLETED`,
  `FAILED`, `RESUBMITTED`) and `completionAttempts`.
- **Plain Spring wiring** (in `saga-forge-spring-boot-starter`, this transport only): `SpringTransactionBoundary` over
  `PlatformTransactionManager` (joins an active Spring transaction as the unit of work's `LocalTransaction`, exposes the
  `Connection` bound by `DataSourceUtils`), the transport's executor from Boot's `ThreadPoolTaskExecutor` builder, and a
  `SmartLifecycle` that starts the transport after the engine and stops it first. Optional bridge (FR-027): after an
  event entry is consumed, re-publish the event through `ApplicationEventPublisher`, outside any transaction, swallowing
  and logging listener failures.
- **Interop mode** (`ModulithMessageStore`, a `MessageStore` decorator): `append` stores each message in the registry with
  one fixed target identifier (`io.github.gkosharovdev.sagaforge.transport`) by calling `registry.store(envelope, …)`
  directly — not by publishing an application event — inside the unit of work's Spring transaction, and inserts the
  side record (§8) in the same transaction. The relay reads due side records, fetches payloads from
  `findIncompletePublications()` filtered by that target identifier and matched by envelope id, and calls
  `markCompleted` on consumption and `markFailed` when an entry is set aside. Ordering, attempts, backoff, set-aside and
  queries come from the side records only (FR-028a). Retention calls `deleteCompletedPublicationsOlderThan`.
  - Saga Forge registers no `@TransactionalEventListener` for its messages, so the registry's listener interception,
    `republish-outstanding-events-on-restart` and the staleness monitor do not act on them; the docs warn the host not to
    point resubmission at Saga Forge's target identifier.
  - The envelope (`SagaForgeEnvelope`: message id, kind, payload) must serialize deterministically, because
    `markCompleted(event, target)` looks the publication up by event. This is verified first in the implementation (a
    Testcontainers spike) — **risk R1**; if the lookup proves unreliable, fall back to marking by publication id through
    `EventPublicationRepository`, still within "store, complete, list".
  - Detection: interop mode is on when the starter finds an `EventPublicationRegistry` bean and
    `saga-forge.transport.modulith-interop` is not `false`. `saga-forge-core` and the transport module know nothing of
    it (Principle I).
- **Alternatives**: deliver through a Saga Forge `@TransactionalEventListener` so Modulith drives delivery — its
  completion happens when the listener returns, so ordering, set-aside and per-key blocking would have to live inside
  a listener that cannot block the committing thread (FR-009). Rejected.

## §11 Quarkus

- **Finding**: Quarkus offers CDI events (`jakarta.enterprise.event.Event`, `@Observes`, `@ObservesAsync`),
  transactional observers (`@Observes(during = TransactionPhase.AFTER_SUCCESS)`), the Vert.x event bus
  (`@ConsumeEvent`) and SmallRye Reactive Messaging with an in-memory connector — but no persisted publication registry
  like Spring Modulith's. A Quarkus host therefore always uses Saga Forge's own log.
- **Decision**: nothing in core, engine, transport or the Postgres log may use Spring types; the contracts `MessageStore`,
  `TransactionBoundary` and an injectable `Executor` are all a Quarkus extension needs (Narayana `TransactionManager` as
  boundary, Agroal `DataSource`, `ManagedExecutor`, `StartupEvent`/`ShutdownEvent`). Deferred: G-007-01.

## §12 Single instance (FR-021)

- **Decision**: leases already stop two transports from delivering the same entry at the same time; duplicates after a
  lease expiry are absorbed by §6. On start the transport upserts a heartbeat row (consumer id, host, started, last seen;
  refreshed every 30 s) and logs a WARNING if another consumer's heartbeat is younger than 90 s.

## §13 Testing strategy

- Unit tests of the relay and transport against an in-memory `MessageStore` double (test-jar), with a fake clock, covering
  ordering per key, backoff, set-aside, non-blocking of other keys, shutdown and restart (a new transport over the same
  double).
- Engine tests for outbox commands (buffering, rollback, `CommandId` preserved, `committed` hook) over the existing
  in-memory doubles, plus property tests for Property 35 (jqwik).
- Postgres container suites (`@Tag("container")`): `PostgresMessageStore` contract tests; fault injection (SC-001/SC-002) by
  stopping the transport at each step boundary and starting a new one on the same database; 10,000-entry restart (SC-005).
- Starter tests with Spring Boot test slices; interop suite with Spring Modulith JDBC on Testcontainers Postgres.
- SC-008: one abstract acceptance suite with three concrete setups.

## §14 Renaming the 004 handoff SPI to outbox vocabulary

- **Decision**: Before any other 007 change, rename the 004 handoff SPI to the transactional-outbox terms the
  messaging community uses (outbox, message relay, publish/send). This is a pure rename with no behaviour change:

  | Until 007 | From 007 |
  |---|---|
  | `core.spi.EventHandoff` (`handOff(batch)`, `timing()`, `Timing`) | `core.spi.Outbox` (`store(batch)`, `timing()`, `Timing`) |
  | `core.spi.HandedOffEvents` | `core.spi.OutgoingMessages` |
  | (new in 007) | `core.spi.OutgoingCommand` |
  | `engine.DispatchingEventHandoff` | `engine.ImmediateDispatchOutbox` |
  | `SagaEngine.Builder.handoff(...)` | `SagaEngine.Builder.outbox(...)` |
  | `SagaHandlingResult.handoffFailures()` | `SagaHandlingResult.outboxFailures()` |
  | test double `CapturingEventHandoff` | `CapturingOutbox` |

  Engine-internal and test identifiers containing "handoff" (about 30 names, for example `handoffTiming`,
  `HandoffObligationsTest`) are renamed in the same change. `Outbox.committed(batch)` and `supportsCommands()` are
  the 007 additions (§2, §3) on the renamed type.
- **Naming the default**: `ImmediateDispatchOutbox` is not a durable outbox. It dispatches right after commit and is
  at most once (G-ENG-07). Its name says so instead of hiding it.
- **Rationale**: "handoff" names a moment, not a role; with `IN_TRANSACTION` timing the SPI *is* an outbox, and after
  007 it carries commands too, so "Event" in the name is wrong. The project has no external users yet, so the rename
  is cheapest now.
- **Traceability**: specs 004 to 006 stay as written (historical record). A note is added at the top of
  `specs/004-saga-engine/spec.md` and `contracts/` mapping the old names to the new ones; `docs/architecture.md`,
  `README.md`, `AGENTS.md`, the `.kiro` design docs and the Javadoc use the new names.
- **Alternatives**: keep `EventHandoff` and add `Outbox` as an alias. Rejected: two names for one SPI. `Publisher` or
  `Dispatcher` were also rejected because `EventPublisher` and `EventDispatcher` already exist with other meanings.

## §15 "Lease" instead of "claim"

- **Decision** (user, 2026-11-11, before the first commit): the message store's reservation vocabulary is *lease*
  throughout: `MessageStore.lease(consumerId, now, leaseDuration, limit)` returns `LeasedMessages`; rows carry
  `leasedBy`/`leasedUntil` (`leased_by`/`leased_until`); `renew` and `release` complete the lifecycle; settings are
  `leaseDuration` and `leaseBatchSize`. The relay's loop is called the polling loop, after the Enterprise Integration
  Patterns' Polling Consumer; the relay keeps the outbox pattern's name *Message Relay*.
- **Rationale**: "claim" is job-queue jargon and collides with EIP's unrelated *Claim Check*; "lease" is the
  established distributed-systems term (also Google Pub/Sub's client "lease management") and makes reservation,
  expiry, renewal and release one concept. `poll`/`fetch`/`receive` were rejected because in Kafka, `java.util.Queue`,
  SQS and Pub/Sub they do not convey the reservation.

## §16 `MessageStore` and `CommandDelivery.OUTBOX`

- **Decision** (user, 2026-11-11, during PR review): the log contract is `MessageStore` (package `core.spi.store`),
  after the *Enterprise Integration Patterns* Message Store; a stored message is `StoredMessage`, a message handed to
  `append` is `NewMessage`; implementations are `PostgresMessageStore`, `ModulithMessageStore` and the test double
  `InMemoryMessageStore`; operator access is `MessageStoreQueries`; the tables are `saga_message_store` and
  `saga_message_store_consumer`. Commands that travel through the store are `@Command(delivery =
  CommandDelivery.OUTBOX)`, delivered to `CommandReceiver` as an `OutboxCommand`; prose says "outbox command".
- **Rationale**: "log" suggested an append-only audit trail or a logger; "Message Store" is the pattern's own name and
  already headed the architecture's pattern table. `OUTBOX` tells a developer what to expect (sent with the
  transaction, handled after commit, at least once) and matches the `Outbox` SPI; `STORED` named an internal
  mechanism, and `ASYNCHRONOUS` suggests fire-and-forget.

## Risks

- **R1**: Spring Modulith's lookup by event in `markCompleted` (§10). Spike first.
- **R2**: SC-003 under load is not measured in CI; checked on an idle setup only.
- **R3**: per-key ordering is by aggregate, not by saga instance (G-007-04).
