# Research: Process Metadata Propagation

**Feature**: `specs/019-process-metadata` | **Date**: 2026-11-27

Each entry: Decision, Rationale, Alternatives considered. The spec's decisions D1 to D11 are not reopened here; this
file settles how they are built.

## R1 — Name and shape of the host SPI: `MetadataAccessor` with a `Scope`

**Decision**: core SPI `core.spi.MetadataAccessor` with two methods, `SagaMetadata capture()` and
`MetadataAccessor.Scope restore(SagaMetadata metadata)`, where `Scope extends AutoCloseable` and its `close()` throws
no checked exception. A constant `MetadataAccessor.NONE` captures empty metadata and restores nothing. The roadmap's
working name `MetadataProvider` is not used.

**Rationale**: the SPI does two things, read the thread's context and put a map back for a while. "Provider" names
only the first. The closest well-known shape is Micrometer Context Propagation's `ThreadLocalAccessor` (`getValue`,
`setValue`, `restore`), which Spring Boot users meet through Micrometer Tracing; the scope object follows
OpenTelemetry's `Scope` returned by `Context.makeCurrent()`, which the runtime already uses (010). Familiarity: high
(both are mainstream in the Spring and OpenTelemetry ecosystems). A try-with-resources scope makes "put back what the
thread held before" (FR-002, User Story 1 scenario 5) impossible to forget.

**Alternatives considered**: Axon's `CorrelationDataProvider` (capture only; Axon restores nothing into logging, so it
does not cover D5). Two separate SPIs, one to capture and one to restore (ISP-pure, but every real implementation does
both over the same thread context and must agree on the keys; splitting invites mismatched pairs). Depending on
Micrometer Context Propagation from core (breaks Principle I).

## R2 — The value: `core.metadata.SagaMetadata`

**Decision**: a final, immutable record `SagaMetadata(Map<String, String> entries)` in a new package
`core.metadata`, with `empty()`, `of(Map)` (strict: throws `IllegalArgumentException` on a blank key, a null value or
any bound exceeded), `sanitized(Map, Consumer<String> droppedKey)` (lenient: drops offending entries and names each
dropped key once), `get(String)` → `Optional<String>`, `isEmpty()` and `asMap()` (unmodifiable). Public constants
`MAX_ENTRIES = 32`, `MAX_KEY_LENGTH = 128`, `MAX_VALUE_LENGTH = 1024`. Entries are kept in key order
(`TreeMap` copy) so equality, `toString` and JSON are stable.

**Rationale**: one value type is read by saga code (D7), carried by messages, instances and entries, and stored. The
strict factory serves applications and tests; the lenient one serves every capture path (runtime, transport,
starter, fixture), so the bounding rule of D8 lives in one place instead of being copied (Effective Java Items 1, 17,
49). The user chose the name `SagaMetadata` (D7); it is also used for a command sent outside any saga, which reads
naturally as "the metadata of the process this command belongs to".

**Alternatives considered**: a bare `Map<String, String>` everywhere (no place for the bounds, easy to confuse with
`SagaContext` maps); a class with a builder (no optional parts, a record is enough).

## R3 — How a transport hands metadata to the runtime: `EventDelivery`

**Decision**: core gains `core.spi.EventDelivery(Object event, String sourceSagaId, SagaMetadata metadata)`
(`sourceSagaId` nullable, metadata never null) and a default method `EventReceiver.receive(EventDelivery)` that ignores
the metadata and calls `receive(event, sourceSagaId)` or `receive(event)`. The runtime implements it as its main entry.
Its `receive(Object)` and `receive(Object, String)` capture metadata through the accessor on the calling thread and
then build an `EventDelivery`. The in-memory transport and the fixture's `LoopBackTransport` call the new method.

**Rationale**: the hop must be explicit: a delivery carries its metadata as data, so the runtime does not depend on the
host's thread context to carry it between transport and runtime. A parameter object instead of a third overload
(`receive(event, source, metadata)`) keeps the receiver from growing a long parameter list, and the next field
(for example a Kafka partition) goes into the record (Effective Java Item 51). The capture in the old methods keeps
host code that calls `receive(event)` directly (the starter's in-process route, the `ImmediateDispatchOutbox`)
working: the host thread is where the metadata is. Decorators of `EventReceiver` must forward the new method, as 014
required for `receive(event, sourceSagaId)` (recorded as G-019-04).

**Alternatives considered**: relying only on the thread context (transport restores, runtime captures): works only with
an accessor that captures every key the transport restored, which an MDC accessor limited to configured keys does,
but a decorator or a second accessor could silently lose keys; also the fixture would need a thread-local accessor
just to move data. A Saga Forge-owned static thread-local in core (`SagaMetadata.current()`): this is the static
accessor the user rejected for application code (D7), and two modules (runtime, transport) would share global state.

## R4 — Where the instance's metadata comes from and when it is written

**Decision**: the router takes the metadata of the start event's `EventDelivery` and writes it with the creation write
(`SagaInstance.metadata`). An instance adopted on a redelivered start event (015) or found by a duplicate start keeps
its stored metadata. A later event's delivery metadata goes onto that event's queue entry, so the step it triggers
records it on its `SagaLifecycleEntry` (D3).

**Rationale**: D2 fixes the metadata at the start; the creation write is the first durable record of the instance, so
the metadata survives a crash between creation and the first unit of work (015's adoption path restores it).

**Alternatives considered**: writing it in the first unit of work (lost when the creation write is adopted after a
crash; the adoption would need the start message's metadata again).

## R5 — Restoration scope: one per unit of work, one per outbox command, one per relay delivery

**Decision**:
- `UnitOfWork` opens `accessor.restore(metadata)` around the whole run of a unit of work (all attempts of
  `AttemptLoop` included) and closes it in `finally`. Instance scopes use the instance's metadata (read under the
  lock), standalone scopes use the metadata of the work they run: the outbox command's for `HandleCommand`, the
  capture for a host `CommandGateway.send` outside any unit of work.
- The relay opens `accessor.restore(message.metadata())` around each delivery: the call into `EventReceiver`,
  `CommandReceiver`, and the `ApplicationEventBridge`/listener hand-off of outcome events.
- An accessor failure in `restore` or `close` is reported once per unit of work through `Diagnostics`
  (`saga.metadata.restore.failed`) and swallowed; a failure in `capture` yields `SagaMetadata.empty()` with
  `saga.metadata.capture.failed` (FR-005). Dropped entries are reported as `saga.metadata.entry.dropped` naming the
  key, never the value (FR-024).

**Rationale**: a unit of work is the unit of thread confinement in the runtime (WorkContext is confined to it, 011);
restoring once covers steps, in-transaction handlers, compensations, deadline steps, retried attempts and the
recorded events the starter publishes to Spring inside the unit of work (D5) without touching each call site. Nested
scopes are harmless: the relay's scope and the unit of work's scope restore the same map for a relay-delivered event,
and the inner scope puts back the outer one on close.

**Alternatives considered**: restoring around each user-method invocation (many call sites, and recorded-event
listeners would run outside it); restoring only in the relay (direct `receive` calls and operator repairs would run
without it).

## R6 — Reading in code: the `SagaMetadata` field and handler parameter

**Decision**: `SagaModel` finds the optional `SagaMetadata` field like `SagaContext`/`CommandGateway`
(`Reflection.fieldOfType`) and `WorkContext.newSagaObject()` injects the unit of work's metadata through `Injection`.
The scanner rejects a second field (R32). `EngineCommandGateway` supplies a `SagaMetadata` handler parameter beside
`CommandId` (it already special-cases `CommandId`), with the current unit of work's metadata; the scanner's host rules
reject two such parameters on one handler (R33, `validateAll` only). Neither is a `HandlerParameterResolver` concern.

**Rationale**: the user chose this shape (D7); reusing the existing type-recognised field and engine-supplied
parameter patterns keeps one mechanism per role (Principle II).

**Alternatives considered**: settled by the user (parameter on every method, static accessor).

## R7 — Messages produced outside a saga

**Decision**: the in-memory transport's `publish` (an `EventPublisher` outside any unit of work) captures through its
accessor. A host `CommandGateway.send` outside any unit of work runs a standalone unit of work in the runtime, which
captures before it starts. The starter's `InboundSagaEventListener` (014) stores events on the publishing thread, so
the transport capture applies there too.

**Rationale**: FR-006 and FR-020; capture happens on the thread that produced the message, the only place where the
request's context still exists.

## R8 — Storage: three JSONB columns in `V6`

**Decision**: migration `V6__process_metadata.sql` adds `metadata JSONB NOT NULL DEFAULT '{}'::jsonb` to
`saga_instance`, `saga_lifecycle_history` and `saga_message_store`. `schema.sql` gains the same columns;
`schema-upgrade-019.sql` adds them with `ADD COLUMN IF NOT EXISTS`; `schema-levels/V6.json` is regenerated with
`SchemaSnapshotTest -Dsaga-forge.write-snapshots=true`; `PostgresSchema.versions()` gains 6 and
`released-migrations.sha256` stays as it is (V6 is unreleased until 0.x ships it). `JsonCodec` writes the map as a
JSON object of strings; reading `NULL` or `{}` gives `SagaMetadata.empty()`.

**Rationale**: 018's rule for schema changes (AGENTS.md); `NOT NULL DEFAULT '{}'` makes every pre-upgrade row read as
empty (FR-015, SC-004) without a data rewrite, which 018's detection cannot see (G-018-02). No index: querying by
metadata value is out of scope (G-019-02).

**Alternatives considered**: a `saga_metadata` side table keyed by saga id (needs joins in listings and a separate
history shape; JSONB is how trace context, triggers and deadlines are stored already).

## R9 — Spring Modulith interop

**Decision**: `ModulithMessageStore` keeps metadata in its delivery records (`PostgresMessageStore.appendDeliveryRecord`
writes the new column like the rest of the record) and puts it on the `StoredMessage` it leases. Modulith's own
publication registry does not see it; a Modulith listener therefore runs with the metadata only when the starter's
inbound route or the relay restores it, not on Modulith's own republication threads (G-019-03, like G-010-03 for
traces).

**Rationale**: FR-016; the delivery records are Saga Forge's, the registry is Modulith's.

## R10 — Starter: an MDC accessor behind configured keys, SLF4J optional

**Decision**: `io.github.gkosharovdev.sagaforge.spring.metadata.MdcMetadataAccessor` (package-private class, public
configuration `SagaForgeMetadataConfiguration`) is registered when `org.slf4j.MDC` is on the class path and
`saga-forge.metadata.mdc-keys` is non-empty, and backs off for an application `MetadataAccessor` bean.
`capture()` reads each configured key from `MDC` (absent keys are skipped); `restore()` remembers each key's previous
value, puts the map's values for configured keys, and on close restores or removes each key. Keys in a restored map
that are not configured are not written to MDC (they are still carried and readable). `slf4j-api` becomes an optional
dependency of the starter. The same bean goes to the runtime builder, the transport builder and the inbound listener.
The `sagaforge` endpoint gains `metadata: {accessor, mdcKeys}`.

**Rationale**: D9 and FR-019; MDC stays in the starter (Principle I). Only configured keys are touched, so the starter
never clobbers MDC fields owned by the log backend (trace ids, G-010-04).

**Alternatives considered**: copying the whole MDC (unbounded, picks up framework keys, possibly personal data);
an accessor bean even with no keys configured (pointless calls on every unit of work).

## R11 — Test fixture

**Decision**: `SagaFixture.when(Object event, SagaMetadata metadata)` and `Given.given(SagaMetadata, Object...)`;
`SagaFixture.Builder.metadataAccessor(MetadataAccessor)`; `InstanceView.metadata()`, `RecordedCommand.metadata()`,
`Then.expectMetadata(SagaMetadata)`. `LoopBackTransport` uses `EventDelivery`, so loop-back hops keep metadata. Without
a metadata argument, `when(event)` captures through the fixture's accessor (none by default, so empty).

**Rationale**: FR-022. Outcome messages carry the instance's metadata by construction (FR-008); the fixture collects
outcome events, not their messages (G-011-07), so it exposes no outcome-message metadata. The runtime's own tests
cover that the outcome message carries it; the spec's FR-022 is narrowed accordingly.

## R12 — No new dependency and no telemetry attribute

**Decision**: core and runtime add no dependency (SC-006); metadata is never a span attribute or metric attribute
(FR-024). Diagnostics name dropped keys only.

## R13 — Kafka and other gaps recorded

New gaps for memory.md: G-019-01 a step cannot add metadata; G-019-02 no query by metadata value; G-019-03 Modulith
republication threads do not see metadata; G-019-04 `EventReceiver` decorators must forward `receive(EventDelivery)`;
G-019-05 the Kafka transport must carry metadata like trace context (beside G-010-01); G-019-06 metadata is stored and
shown unredacted (relates to G-ADOPT-03).
