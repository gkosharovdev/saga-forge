# Feature Specification: Process Metadata Propagation

**Feature Branch**: `019-process-metadata`

**Created**: 2026-11-27

**Status**: Planned

**Input**: User description: "019 process metadata propagation (roadmap .specify/memory/roadmap.md "### 019", closes G-ADOPT-05, partly G-010-04). Problem: a saga instance records no correlation data, and handlers do not see the context of the request or message that triggered them; only OpenTelemetry trace context travels (010). Scope: a core SPI MetadataProvider captures a string map when an instance starts and when a message is sent; the map is stored on the instance and in lifecycle history, and carried on messages next to traceContext. Steps, command handlers and compensations can read it (an injected SagaMetadata, or a parameter). Starter: capture from and restore to SLF4J's MDC for configured keys (saga-forge.metadata.mdc-keys). Included in 015's operations view. Whether it is added to outcome events (011's contract carries no context value or saga field) must be decided explicitly. Out of scope: typed metadata, fixed fields such as "initiator". Constitution: Principle I, MDC only in the starter; MessageStore and the Postgres schema gain a metadata column (new Flyway migration V6 per 018). Acceptance: a correlation id set in the MDC of an HTTP request appears in the MDC of every step, handler and compensation of the saga it starts, across transport hops and a restart. Note: booking-service never adopted Saga Forge; treat its W15 as a reported need, no success criterion may need an adopter to confirm."

## Context

A saga instance records nothing about who or what started it, and the code it runs does not see the context of the
request that triggered it (G-ADOPT-05). An HTTP request typically carries a correlation id, a tenant, a request id or a
user id in its logging context. Once the request's event reaches a saga, every step, command handler and compensation
runs on another thread, often after a transport hop or a restart, and that context is gone: log lines of the saga can
no longer be joined to the request, and an operator looking at a stuck instance cannot tell which request it belongs
to. Only the OpenTelemetry trace context travels today (010), and only for tracing.

Mature engines carry such data as message metadata: Axon Framework's `MetaData` with a `CorrelationDataProvider`,
MassTransit's and NServiceBus's message headers. This feature gives Saga Forge the same thing in generic terms: a
string-to-string map, captured by a host-supplied provider when a message is produced or an instance starts, stored
with the instance and its lifecycle history, carried on every message the instance produces, and handed back to the
provider around every piece of saga code so that it can restore the host's logging context. The Spring Boot starter
supplies a provider over SLF4J's MDC for the keys an application configures. Core stays free of any logging library
(Constitution Principle I, G-010-04).

The need was reported by booking-service's migration attempt (feedback SF-10, W15). booking-service never adopted
Saga Forge, so W15 is a reported need, not a workaround in production, and no success criterion depends on it.

## Decisions Taken In This Spec

These settle the open questions in the request and the roadmap brief; each is reflected in the requirements below.

- **D1 — Untyped string map, no fixed fields.** Metadata is an unordered map of non-blank string keys to non-null
  string values. Saga Forge defines no key ("initiator", "tenant", "correlationId" are application keys) and never
  interprets a value. Typed metadata is out of scope.
- **D2 — Captured once per instance, fixed for its life.** An instance's metadata is the metadata of the delivery that
  started it: the map carried on the start event's message, or, when the start event is handed to the runtime on the
  host's own thread without a stored message, the map the provider captures on that thread. It does not change
  afterwards. A later event that reaches the instance with different metadata does not replace it; that event's own map
  is recorded on the lifecycle entry of the step it triggered (D3). Adding keys from a step is out of scope (gap).
- **D3 — History records the triggering metadata.** Every lifecycle-history entry written for a step, compensation or
  deadline step records the metadata of the message that triggered it, so an operator can see which request each step
  answered. Entries written by an operator repair or a recovery sweep record the instance's metadata.
- **D4 — Outgoing messages inherit the instance's metadata.** Every message a unit of work produces for an instance —
  recorded events, outbox commands, deadlines, scheduled commands, command-failure reports and outcome events — carries
  the instance's metadata. A message produced outside any saga (an event published by host code, an outbox command sent
  from a service) carries what the provider captures on the producing thread. A command handler that runs while an
  outbox command is handled sees, and passes on, that command message's metadata.
- **D5 — Restore around all saga code, not per call.** The runtime hands the instance's metadata to the provider for
  the duration of each unit of work (every step, in-transaction command handler, compensation and deadline step it runs,
  including retried attempts) and of each outbox command handling, and the provider removes it afterwards, putting back
  whatever the thread held before. Recorded events published to the host inside the unit of work (008/014) are therefore
  handled with the same context.
- **D6 — Outcome events: envelope only.** Outcome events carry the instance's metadata on the message that carries them
  (D4), never in their payload. `SagaCompleted`, `SagaFailed` and `SagaCompensated` stay unchanged, and 011's rule
  that an outcome event carries no context value or saga field stands (Property 38 unchanged). A listener sees the
  metadata restored in its logging context when it runs (D5, FR-011), in the unit of work for recorded events and around
  the relay's delivery otherwise.
- **D7 — Reading metadata: a saga field and a handler parameter.** A saga reads its instance's metadata through an
  optional, read-only `SagaMetadata` field recognised by declared type, like `SagaContext` and `CommandGateway` (at most
  one per saga, new scanner rule R32); step and compensation signatures are unchanged (R5, R21 stand). A command handler
  declares a `SagaMetadata` parameter, supplied by the runtime like `CommandId`, and sees the metadata in effect for that
  handling: the sending instance's for an in-transaction command, the command message's for an outbox command, the
  producing thread's capture for a command sent outside any saga. There is no static accessor. New correctness
  Property 50 covers the field rule and the value each reader sees.
- **D8 — Bounded and fail-safe.** Metadata is bounded (by default at most 32 entries, keys of at most 128 characters,
  values of at most 1,024 characters; the plan may adjust the numbers). Entries over a bound, and blank keys, are
  dropped with one diagnostic naming the key, never with a failed request or unit of work. A provider that throws while
  capturing yields an empty map with a diagnostic; one that throws while restoring is reported and the saga code still
  runs. Metadata never decides routing, association or deduplication.
- **D9 — Starter: configured MDC keys only, off by default.** The starter captures and restores only the MDC keys named
  in `saga-forge.metadata.mdc-keys` (empty by default, so nothing is captured unless the application opts in). An
  application bean of the provider type replaces the MDC provider. Trace and span ids are not copied by default: 010
  already propagates the trace context, and the log backend's tracing integration fills those MDC fields.
- **D10 — Visible to operators.** The operations API (015) returns an instance's metadata with the instance and with each
  history entry, and the `sagas` Actuator endpoint shows it. It is shown by default: an application chooses the keys,
  unlike `SagaContext` values, which stay behind `saga-forge.operations.expose-context-values`. Querying instances by a
  metadata value is out of scope (gap).
- **D11 — Durable and upgrade-safe.** Postgres stores the instance's metadata, each history entry's metadata and each
  stored message's metadata, added by the next versioned migration (018's rule: `V6`, `schema.sql`, an upgrade script,
  the `V6` snapshot). Instances, entries and messages written before the upgrade read back with empty metadata.

## Clarifications

### Session 2026-11-27

- Q: Should outcome events carry the metadata in their payload or only on their message? → A: On the message only
  (envelope); the outcome event records stay unchanged (D6).
- Q: How do steps, compensations and command handlers read the metadata? → A: A read-only `SagaMetadata` field on the
  saga recognised by type, and a `SagaMetadata` parameter on command handlers; no static accessor (D7).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A request's correlation id follows its saga everywhere (Priority: P1)

A Spring Boot application puts a `correlationId` into the logging context of every HTTP request and names it in
`saga-forge.metadata.mdc-keys`. A request places an order, which starts a saga. Every log line written by the saga's
steps, by the command handlers they call (in the step's transaction or through the outbox), by its compensations and by
its deadline step carries the request's `correlationId`, also when a step runs after a relay hop, after a retry or after
the application restarted.

**Why this priority**: it is the reported need and the roadmap's acceptance test; without it the metadata has no
consumer.

**Independent Test**: start the Spring example with the key configured, send one request with a known correlation id
and capture the logging context seen by every step, handler, compensation and deadline step of the saga it starts,
before and after a restart of the application between two steps.

**Acceptance Scenarios**:

1. **Given** `saga-forge.metadata.mdc-keys=correlationId` and a request whose logging context holds `correlationId=c-1`,
   **When** the request records the start event, **Then** every step of the started instance runs with `correlationId=c-1`
   in its logging context, and the context is removed again when the unit of work ends.
2. **Given** a step that sends an outbox command, **When** the relay hands the command to its handler, **Then** the
   handler runs with `correlationId=c-1`, and an event the handler publishes carries it on.
3. **Given** an instance waiting for its next event, **When** the application restarts and the event arrives from a
   request with `correlationId=c-2`, **Then** the step runs with the instance's `correlationId=c-1` and its history entry
   records `c-2` as the triggering metadata.
4. **Given** a `@StartCompensation` step, a failed outbox command or an expired deadline, **When** the instance
   compensates, **Then** every compensation and every command it sends runs with `correlationId=c-1`.
5. **Given** a worker thread that held `correlationId=other` before a unit of work, **When** the unit of work ends,
   **Then** the thread holds `correlationId=other` again.

---

### User Story 2 - An operator sees which request a saga belongs to (Priority: P1)

An operator investigating a `COMPENSATION_FAILED` instance opens it through the operations API or the `sagas`
endpoint and sees its metadata (for example the correlation id and tenant of the request that started it) and, for each
history entry, the metadata of the message that triggered that step.

**Why this priority**: the instance and its history are the durable record; logs rotate, the stored metadata does not.

**Independent Test**: run a saga over Postgres with metadata on the start event and a different map on a later event,
then read the instance and its history through `SagaOperations` and the endpoint.

**Acceptance Scenarios**:

1. **Given** an instance started with `{correlationId=c-1, tenant=t-9}`, **When** an operator finds it, **Then** the view
   shows that map.
2. **Given** a later step triggered by an event carrying `{correlationId=c-2}`, **When** the operator reads the history,
   **Then** that step's entry shows `{correlationId=c-2}` and the instance still shows `{correlationId=c-1, tenant=t-9}`.
3. **Given** an instance written before the upgrade, **When** an operator reads it, **Then** it shows empty metadata and
   nothing fails.

---

### User Story 3 - A host without Spring plugs in its own context (Priority: P2)

A team that assembles the runtime by hand (or uses another logging library) registers its own provider on the runtime
and transport builders. The provider captures from and restores to whatever context the team uses; core and runtime
know nothing about it.

**Why this priority**: keeps the feature generic and core dependency-free (Principle I); Spring is one adapter.

**Independent Test**: assemble the runtime and in-memory transport with a test provider over a thread-local map and
check the same scenarios as User Story 1 without Spring.

**Acceptance Scenarios**:

1. **Given** no provider is registered, **When** sagas run, **Then** metadata is empty everywhere and behaviour is
   unchanged from 018.
2. **Given** a provider that throws while capturing, **When** a start event is handed over, **Then** the instance starts
   with empty metadata and one diagnostic is reported.

---

### User Story 4 - A saga test states and checks metadata (Priority: P2)

A developer writes a fixture test that hands a start event with given metadata to the saga and expects that the
instance holds it, that the commands and events the saga produced carry it, and that a step that reads it behaves
accordingly.

**Why this priority**: every saga feature ships fixture support (009); without it metadata-dependent code cannot be
unit-tested.

**Independent Test**: a fixture test in `saga-forge-example` that starts the canonical saga with metadata and checks
the instance view and recorded commands.

**Acceptance Scenarios**:

1. **Given** a fixture, **When** a test hands over an event with metadata, **Then** the instance view shows it and each
   recorded command and outcome message carries it.
2. **Given** a fixture with a provider, **When** a step runs, **Then** the provider receives the instance's metadata to
   restore and the test can assert on it.

### Edge Cases

- No provider, or the starter with no configured key: metadata is empty; nothing is stored beyond an empty value.
- A key over the length bound, a value over the bound, a blank key, or more entries than allowed: those entries are
  dropped with a diagnostic naming the key (D8); the rest are kept.
- A configured MDC key absent from the request's context: it is not captured (no empty-string entry).
- A start event redelivered after the instance exists (duplicate or adoption of a stranded `CREATED` start, 015): the
  instance keeps the metadata it was created with.
- A retried attempt (005) and an in-process retry wait: the metadata stays restored for every attempt.
- An operator repair or recovery sweep (015) runs compensation on the operator's thread: the instance's metadata is
  restored for the compensations, and the operator's own context is put back afterwards.
- A deadline delivered after a restart, or ignored because the instance left `RUNNING` (017): it carries the instance's
  metadata; an ignored delivery runs no saga code.
- A recorded event fanned out to another saga type (014) starts that saga with the source instance's metadata.
- An outcome event delivered by the relay to a listener: the message carries the instance's metadata (D4, D6).
- Spring Modulith interop (two logs, 014): messages kept in Modulith's registry carry metadata the same way they carry
  trace context; if Modulith's registry cannot hold it, the plan records the limitation as a gap.
- The `ImmediateDispatchOutbox` (no stored messages): metadata is handed to the receiving code on the same thread, so it
  still reaches the instance and its handlers.
- A configured key that the log backend's tracing integration also writes (`traceId`, `spanId`): Saga Forge does not
  filter it; the application's configuration decides, and the guide advises against listing those keys (D9).

## Requirements *(mandatory)*

### Functional Requirements

**Metadata value and provider (core)**

- **FR-001**: Core MUST define an immutable metadata value: a map of non-blank string keys to non-null string values,
  empty when nothing was captured, with no keys defined by Saga Forge (D1).
- **FR-002**: Core MUST define a host-supplied provider SPI with two responsibilities: capture the current thread's
  metadata as a map, and restore a given map for the duration of a scope that, when closed, puts back what the thread
  held before. It MUST NOT depend on any logging or framework library (Principle I).
- **FR-003**: The runtime builder, the in-memory transport builder and the fixture builder MUST accept a provider; with
  none, capture yields empty metadata and restore does nothing.
- **FR-004**: Metadata MUST be bounded as in D8; over-bound and blank-key entries are dropped with a diagnostic, never
  failing the capture, the unit of work or the request.
- **FR-005**: A provider failure while capturing MUST yield empty metadata with a diagnostic; a failure while restoring
  or closing MUST be reported and MUST NOT stop the saga code from running or the unit of work from committing.

**Capture and propagation (runtime, transport)**

- **FR-006**: Every stored message MUST carry metadata next to its trace context. A message produced inside a unit of
  work for an instance carries the instance's metadata; a message produced outside any saga carries what the provider
  captures on the producing thread at the time it is produced (D4).
- **FR-007**: A new instance's metadata MUST be the metadata of the delivery that started it: the start message's
  metadata, or, for a start handed to the runtime without a stored message, the provider's capture on the handing
  thread (D2). It MUST NOT change afterwards.
- **FR-008**: Every message a unit of work produces for an instance — recorded events, in-transaction and outbox
  commands, deadlines, scheduled commands, command-failure reports and outcome events — MUST carry the instance's
  metadata (D4). An event fanned out to another saga type (014) starts that saga with it.
- **FR-009**: When an outbox command is handled, its handler MUST see the command message's metadata, and messages the
  handler produces MUST carry it.

**Restoration around saga code**

- **FR-010**: The runtime MUST restore the instance's metadata through the provider for the whole of each unit of work
  it runs for an instance (steps, in-transaction command handlers, compensations, deadline steps, every retried attempt,
  and recorded events published to the host inside it), including units of work run by operator repairs and recovery
  sweeps, and MUST close the scope when the unit of work ends, also on failure (D5).
- **FR-011**: The transport MUST restore the command message's metadata around the handling of each outbox command, and
  the message's metadata around each delivery it hands to host listeners (outcome events included).
- **FR-012**: A delivery that runs no saga code (ignored, duplicate, late deadline) MUST NOT require restoration.

**Reading metadata in code**

- **FR-013**: A saga MAY declare at most one field of type `SagaMetadata` (recognised by declared type, no annotation);
  the runtime sets it to the instance's metadata before any step, compensation or deadline step of that instance runs.
  The scanner MUST reject a second such field (rule R32), and the runtime MUST NOT persist the field as saga state.
- **FR-013a**: A `@CommandHandler` method or constructor MAY declare one `SagaMetadata` parameter, supplied by the
  runtime (not by a `HandlerParameterResolver`) with the metadata in effect for that handling as defined in D7. A
  handler with two `SagaMetadata` parameters MUST be rejected at startup (rule R33).
- **FR-013b**: A new correctness Property 50 MUST state the field rule and the value each reader sees, and the scanner
  tests MUST cover it (Constitution Principle III).

**Storage and history**

- **FR-014**: `SagaInstance` MUST carry its metadata, and every `SagaLifecycleEntry` MUST carry metadata: the triggering
  message's for step, compensation and deadline-step entries; the instance's for repair, recovery and other entries
  (D3).
- **FR-015**: Postgres storage MUST persist the instance's, each history entry's and each stored message's metadata,
  through a new versioned migration `V6`, `schema.sql`, an upgrade script and the `V6` schema snapshot, without editing a
  released migration (018). Rows written before the upgrade MUST read back with empty metadata.
- **FR-016**: The in-memory message store, the fixture's plug-ins and the starter's Modulith message store MUST keep
  metadata with each message the same way they keep trace context.

**Operations and outcome events**

- **FR-017**: The operations API (015) MUST return the instance's metadata with the instance and each history entry's
  metadata with the history; the `sagas` endpoint MUST show both (D10).
- **FR-018**: Outcome events MUST carry the instance's metadata on their message (D4) and MUST NOT carry it in their
  payload (D6); 011's contract and Property 38 stay unchanged.

**Spring Boot starter**

- **FR-019**: The starter MUST supply a provider that captures and restores the SLF4J MDC keys listed in
  `saga-forge.metadata.mdc-keys` (empty by default), restoring the thread's previous values of those keys on close (D9).
  It MUST back off for an application bean of the provider type, and it MUST be the only place in Saga Forge that
  touches MDC.
- **FR-020**: The starter MUST hand the provider to the runtime and the transport, and capture metadata for events
  published through Spring's `ApplicationEventPublisher` and `EventPublisher` (014's inbound route) on the publishing
  thread.
- **FR-021**: The starter README, the configuration metadata (`ReadmePropertiesTest`) and the `sagaforge` endpoint MUST
  describe the property and whether a provider is active.

**Test fixture and examples**

- **FR-022**: The fixture MUST let a test hand over an event with metadata, accept a provider, and expose the instance's
  metadata on `InstanceView`, the metadata of recorded commands, and an expectation on the instance's metadata.
  (Outcome-message metadata is covered by runtime tests: the fixture collects outcome events, not their messages.)
- **FR-023**: `saga-forge-example` MUST show a fixture test with metadata; `saga-forge-example-spring` MUST configure a
  correlation key and prove User Story 1 in a container test across a relay hop and a restart.

**Telemetry and documentation**

- **FR-024**: Metadata MUST NOT become span attributes or metric dimensions by default (unbounded cardinality, possible
  personal data); diagnostics about dropped entries name the key, never the value.
- **FR-025**: The user guide, `docs/architecture.md`, `docs/agents/saga-forge/SKILL.md`, `docs/agents/AGENTS-snippet.md`
  and `llms-full.txt` MUST describe metadata, the provider, the property and the limitations; `memory.md` MUST record
  G-ADOPT-05 as closed, G-010-04 as partly addressed, and the gaps this spec leaves open.

### Key Entities

- **Metadata**: an immutable string-to-string map of application-chosen keys, bounded, empty by default; carried by
  instances, history entries and stored messages.
- **Metadata provider**: host-supplied capture-and-restore strategy over the host's own thread context (MDC in the
  starter); core and runtime only call it.
- **Saga instance**: gains its start metadata, fixed for its life.
- **Lifecycle entry**: gains the metadata of the message that triggered it.
- **Stored message**: gains metadata next to its trace context.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: In the Spring example, a correlation id set on one request appears in the logging context of 100% of the
  steps, command handlers, compensations and deadline steps of the saga it starts, across at least one relay hop and one
  application restart, and in none of the threads after their work ended.
- **SC-002**: An operator can tell which request started any instance, and which message triggered each of its steps,
  from the operations API alone, without reading logs.
- **SC-003**: A host that registers no provider sees no change in behaviour: the whole existing test suite passes
  unchanged apart from tests that construct the changed values.
- **SC-004**: A database at schema version 5 upgrades to 6 by migration or by the upgrade script, and every instance,
  history entry and message written before reads back with empty metadata.
- **SC-005**: An over-bound or malformed metadata entry, or a failing provider, never fails a request or a unit of work
  (0 failures in the fault-injection tests), and is reported once.
- **SC-006**: `saga-forge-core` and `saga-forge-runtime` gain no new dependency.

## Assumptions

- The roadmap's order is followed as the user asked: 019 is taken while 015b (retry from the failed step) is still open;
  nothing in this feature depends on it.
- Metadata is small, application-chosen context (correlation, tenant, request ids). Personal data in it is the
  application's choice and responsibility; Saga Forge does not redact it (failure-text redaction stays deferred,
  G-ADOPT-03).
- The default bounds in D8 are generous for correlation data; the plan may change the numbers but not the drop-and-report
  behaviour.
- The Kafka transport, when built, must carry metadata like trace context (new gap, like G-010-01).
- Adding keys from a running step, querying instances by metadata value and metadata as telemetry attributes are out of
  scope and recorded as gaps.
- Existing correctness properties stand: Property 38 (outcome events) is unchanged by D6, and R5, R21 and Property 10
  are unchanged by D7.
