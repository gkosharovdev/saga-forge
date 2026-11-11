# Feature Specification: In-Memory Transport with a Durable Message Log

**Feature Branch**: `007-in-memory-transport`

**Created**: 2026-11-11

**Status**: Implemented (2026-11-11)

**Input**: User description: "in-memory transport of commands and events. Later we will have kafka as a message transportation provider. However I'd like to specify first the in-memory which is going to be used for moduliths where all java modulles are running on the same machine and the author was deploying only one instance for example. At the same time it has to be possible in face of restart to consume unconsumed messages so despite in-memory transport we need to have some sort of event and command log which are persistable"

## Context

Saga Forge has a working engine (004), retry (005) and compensation (006), but no transport: nothing
implements `EventDispatcher`, so a committed unit of work's events are given only to whatever a host or test
wires in. The default outbox (`ImmediateDispatchOutbox`, called `DispatchingEventHandoff` until 007) is at most once: if the process stops between the commit and the dispatch, the
events are lost and nothing redelivers them (G-ENG-07).

The first transport targets the **modulith**: one deployable application, several Java modules running in the same
process, one running instance. Delivery between modules happens in memory — no broker, no network hop — but a
restart must not lose a message. Every event (and command, see FR-010) that leaves a unit of work is therefore
first written to a **durable message store** in the same transaction as the saga state that produced it, and only
then delivered in memory. After a restart, every message not yet consumed is delivered again.

The Kafka transport will come later and replace only the delivery path. The store's vocabulary — message
identity, recording order, consumed/unconsumed, redelivery — is defined here so that both transports share it and
the saga model, the annotations and application code stay unchanged when a host switches from one to the other.

Running example: the canonical `OrderProcessingSaga` in a modulith with an `order` module (saga, `Order`
aggregate) and a `payment` module (`PaymentService`). `OrderCreatedEvent` starts the saga; `processPayment`
asks for payment; the saga waits for `PaymentProcessedEvent`.

## Clarifications

### Session 2026-11-11

- Q: Which commands go through the store? → A: Commands stay synchronous by default; a command type opts into the store
  through a framework-owned declaration, and only those are handled asynchronously after the sender commits
  (FR-010, FR-010a).
- Q: How long are consumed messages kept? → A: For a configurable period, default 7 days, then removed
  automatically (FR-019).
- Q: How should the in-memory transport relate to Spring and Quarkus? → A: Saga Forge owns the store and the
  in-process relay with no framework dependency; Spring and Quarkus get thin optional adapters that only wire the
  host's transaction manager, executor and lifecycle; when Spring Modulith is present, its event publication
  registry serves as the store instead of Saga Forge's own (FR-026, FR-027, FR-028).
- Q: In Spring Modulith interop mode, what happens with guarantees the registry cannot provide? → A: The registry
  holds the messages; Saga Forge keeps its own delivery bookkeeping (attempts, last failure, set-aside, per-instance
  order) in a side record keyed by the registry's publication id, so every guarantee holds unchanged (FR-028a).
- Q (during planning): Is there messaging vocabulary clearer than "handoff"? → A: Yes; the 004 SPI is renamed to
  transactional-outbox terms: `EventHandoff` → `Outbox`, `handOff` → `store`, `HandedOffEvents` → `OutgoingMessages`,
  `DispatchingEventHandoff` → `ImmediateDispatchOutbox` (research §14).
- Q: Which framework integrations does this feature deliver? → A: The plain-Java transport plus the Spring wiring
  for this transport only (transaction manager, executor, lifecycle, Spring Modulith interop) in the starter
  module; full starter autoconfiguration and a Quarkus extension are later features, and the constitution is
  amended to list Quarkus as an in-scope host (FR-029).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Events between modules are delivered in memory (Priority: P1)

A developer building a modulith adds the in-memory transport to the application. Without running a broker, an
event recorded by one module's unit of work reaches the sagas of other modules that react to it, after that unit
of work commits, and the saga runs its step. The developer writes no transport code and changes no saga.

**Why this priority**: without any transport a saga cannot progress across units of work at all; this is the
smallest slice that makes the framework usable in a single-instance application.

**Independent Test**: start an application with the in-memory transport and the canonical order saga, publish
`OrderCreatedEvent` from outside any unit of work, and observe that the saga reaches its wait point and then,
after the payment module records `PaymentProcessedEvent`, ends `COMPLETED` — with no broker running.

**Acceptance Scenarios**:

1. **Given** the transport is running and the order saga is registered, **When** application code publishes
   `OrderCreatedEvent` outside any unit of work, **Then** a saga instance is started and its first unit of work
   runs.
2. **Given** a unit of work commits and recorded an event that a step of a different saga instance reacts to,
   **When** the commit returns, **Then** that event is delivered to the engine and the other instance runs its
   step in a new unit of work.
3. **Given** a unit of work rolls back, **When** it recorded events, **Then** none of those events is delivered,
   now or after a restart.
4. **Given** an event that no registered saga reacts to, **When** it is delivered, **Then** it is marked consumed
   and never delivered again.

---

### User Story 2 - Unconsumed messages survive a restart (Priority: P1)

The operator restarts (or the process crashes) while messages are in flight: some events were committed but not
yet delivered, others were being handled when the process stopped. When the application starts again, every
message that had not been consumed is delivered, in its original order, and the affected sagas continue as if
there had been no restart.

**Why this priority**: this is the user's explicit requirement and the reason the store exists. Without it an
in-memory transport silently loses saga progress on every restart (G-ENG-07).

**Independent Test**: commit a unit of work that records `PaymentProcessedEvent`, stop the process before the
event is delivered (fault injection), start a new process against the same storage, and observe that the event is
delivered and the saga ends `COMPLETED` exactly once.

**Acceptance Scenarios**:

1. **Given** a unit of work committed and recorded events, **When** the process stops before any of them was
   delivered, **Then** after restart every one of them is delivered, in recording order.
2. **Given** an event was being handled when the process stopped and the handling unit of work did not commit,
   **When** the process restarts, **Then** the event is delivered again.
3. **Given** an event whose handling committed but whose consumption was not yet recorded when the process
   stopped, **When** the process restarts and delivers it again, **Then** the saga does not run the step a
   second time (the engine reports it as a duplicate, 005) and the message is marked consumed.
4. **Given** the store contains no unconsumed message, **When** the process restarts, **Then** nothing is
   delivered and startup is not delayed.

---

### User Story 3 - Commands travel through the same log (Priority: P2)

A saga step in the `order` module sends `ProcessPaymentCommand`, handled by `PaymentService` in the `payment`
module. The command is recorded in the durable log and handled by its handler with the same durability as events:
a restart never loses a sent command, and a command is never handled for a unit of work that rolled back.

**Why this priority**: the user asked for a log of commands as well as events, and the later Kafka transport
needs commands that can cross process boundaries. It is P2 because events alone already make sagas progress and
survive restarts.

A command type that has not opted in keeps today's synchronous handling inside the step's unit of work.

**Independent Test**: a step sends a command whose type opted into the store (FR-010), the process stops after the
step's unit of work commits and before the handler runs; after restart the handler runs once, receiving the same
`CommandId` as before.

**Acceptance Scenarios**:

1. **Given** a step sends a outbox command and its unit of work commits, **When** the commit returns, **Then**
   the command is handled by its single `@CommandHandler`.
2. **Given** a step sends a outbox command and its unit of work rolls back, **When** the rollback completes,
   **Then** the command is never handled.
3. **Given** a outbox command is redelivered after a restart, **When** its handler runs, **Then** it receives the
   same `CommandId` as on the first delivery, so it can pass it on as an idempotency key.
4. **Given** the handler of a outbox command records domain events, **When** its unit of work commits, **Then**
   those events go through the store like any other event.
5. **Given** a step sends a command whose type has not opted in, **When** the handler throws, **Then** the step's
   unit of work rolls back and retry or compensation applies as today (005, 006); nothing is written to the store.

---

### User Story 4 - The operator can see what the store holds (Priority: P3)

When a saga seems stuck, the operator can find out whether its next message is still waiting in the store, was
consumed, or keeps failing and has been set aside — without reading storage tables directly.

**Why this priority**: diagnostics, not correctness; useful once the transport runs in production.

**Independent Test**: produce one consumed, one unconsumed and one set-aside message and query the store; each is
reported with its status, type, attempt count and, for the set-aside one, the last failure.

**Acceptance Scenarios**:

1. **Given** messages in every status, **When** the operator queries the store, **Then** each message is reported
   with its identity, kind (event or command), type, status, recording time, delivery attempts and last failure.
2. **Given** a message was set aside after repeated failures, **When** the operator asks for it to be delivered
   again, **Then** it is delivered again and its status updated by the result.

### Edge Cases

- **Crash between commit and delivery**: the message is in the store as unconsumed; delivered after restart
  (User Story 2). This closes G-ENG-07 for hosts using this transport.
- **Crash after the handling unit of work committed, before consumption is recorded**: redelivered; the engine's
  duplicate detection (005, `HandlingOutcome.DUPLICATE`) keeps the step from running twice. For an event
  starting a saga, a redelivery finds the instance and is reported as a duplicate start — which is correct
  when the first unit of work committed, but strands an instance whose first unit of work never committed (G-ENG-05,
  G-005-06); this feature does not change that rule.
- **The engine reports that no outcome could be recorded** (`EventHandlingException`, storage unavailable): the
  message stays unconsumed and is delivered again later (FR-014).
- **A message keeps failing**: after the bounded number of delivery attempts it is set aside, not retried
  forever, and stops blocking the messages behind it (FR-015, FR-016).
- **A message type no longer exists** (the class was renamed or removed between releases): the message cannot be
  read back; it is set aside with that reason and the rest of the store continues.
- **A saga instance waits for an event that arrives while it is compensating**: delivered like any other event;
  the engine resumes compensation (006). A `COMPENSATING` instance with no further event still never resumes
  (G-006-05) — not addressed here.
- **Events published outside any unit of work** (`EventPublisher.publish` from a request handler): written to the
  log in their own transaction before delivery, so they are durable too; if the write fails, the caller gets the
  failure and nothing is delivered.
- **Many messages arrive while one saga instance is busy**: messages for that instance are delivered in order,
  one at a time; messages for other instances are not held up by it.
- **A second instance of the application is started by mistake** against the same log: see FR-021.
- **Graceful shutdown**: delivery stops taking new messages; a message being handled either finishes or stays
  unconsumed, so nothing is lost.
- **The store grows without bound**: consumed messages are removed according to the retention rule (FR-019).

## Requirements *(mandatory)*

### Functional Requirements

**Recording messages**

- **FR-001**: Every event a committed unit of work passes to its outbox (the 004 outgoing set: events no step of the owning
  instance ran in-process) MUST be written to the message store inside that unit of work's transaction, so the store
  entry exists if and only if the unit of work committed.
- **FR-002**: An event published through `EventPublisher` outside any unit of work MUST be written to the store
  before it is delivered, in a transaction of its own; if the write fails, the failure MUST reach the caller and
  the event MUST NOT be delivered.
- **FR-003**: Each logged message MUST carry a stable identity, unique across the store, derived from the unit of
  work that recorded it and its position in that unit of work (the 004 `(unitOfWorkId, index)` key), plus its
  kind (event or command), its type, its payload, the recording time and, for a saga-scoped unit of work, the
  saga type and id that produced it.
- **FR-004**: A message's payload MUST round-trip through the store: after a restart, the delivered object MUST be
  equal in content to the recorded one for every event and command type the canonical example and the existing
  test models use.
- **FR-005**: The message store MUST live in the same datastore as the saga's state (the configured
  `StorageProvider`), so that FR-001 needs no distributed transaction. In Spring Modulith interop mode (FR-028) the
  registry MUST be in that same datastore and transaction.

**Delivery**

- **FR-006**: After the recording transaction commits, the transport MUST deliver each message to its consumer in
  the same process without waiting for a poll interval: events to the engine's `EventReceiver`, commands to
  their `@CommandHandler` (FR-010).
- **FR-007**: Messages recorded by one unit of work MUST be delivered in recording order; messages that concern the
  same saga instance MUST be delivered in the order they were recorded, one at a time.
- **FR-008**: Delivery MUST be at least once: a message is marked consumed only after its consumer returned
  normally (for an event, the engine produced a `HandlingReport`; for a command, the handler's unit of work
  committed). A message whose consumption is not recorded MUST be delivered again.
- **FR-009**: Delivery MUST NOT run on the thread or inside the transaction of the unit of work that recorded the
  message, so a consumer never sees uncommitted state and the recording unit of work's caller is not held up by
  the consumers' work.

**Commands**

- **FR-010**: Commands sent through `CommandGateway` MUST stay synchronous by default, handled inside the
  sender's unit of work exactly as today (002, 005, 006 unchanged). A command type MUST be able to opt into the store
  through a framework-owned declaration on the command or its handler (Constitution Principle II); a command that
  opted in is a **outbox command**: `send` writes it to the store in the sender's transaction (FR-001) and returns,
  and the command is handled after that transaction commits. Opting in is a model property: the scanner MUST
  validate it, and the rule MUST be added to `.kiro/specs/saga-forge-framework/design.md` with its correctness
  property before implementation (Constitution Principle III).
- **FR-010a**: A outbox command sent outside any unit of work MUST be written to the store in a transaction of its
  own before `send` returns, as FR-002 does for events.
- **FR-011**: A outbox command MUST be handled by exactly the single `@CommandHandler` for its type (Properties
  14–15) in a unit of work of its own; events that handler records MUST be logged as in FR-001.
- **FR-012**: A outbox command MUST keep the `CommandId` assigned when it was sent (005) across redelivery and
  restart, and its handler MUST receive that same identity.
- **FR-013**: A failure of a outbox command's handler MUST NOT fail or roll back the saga step that sent it (that
  unit of work has already committed); it is a delivery failure handled by FR-014 to FR-016.

**Failure handling**

- **FR-014**: When a consumer fails (throws, or the engine reports `EventHandlingException`), the message MUST stay
  unconsumed, its attempt count MUST be incremented and the failure recorded, and it MUST be delivered again after
  a bounded wait.
- **FR-015**: After a configurable maximum number of failed delivery attempts (default 10), the message MUST be set
  aside with its last failure, MUST NOT be delivered again automatically, and MUST be reported as set aside.
- **FR-016**: A message that keeps failing or is set aside MUST NOT block delivery of messages that concern other
  saga instances; it MUST block later messages of the same saga instance only while it is being retried, not once
  it is set aside.
- **FR-017**: A saga that ends `FAILED`, `COMPENSATED` or `REQUIRES_INTERVENTION` as the result of a delivered event
  is a normal outcome: the message MUST be marked consumed, not retried.

**Restart and lifecycle**

- **FR-018**: At startup, before accepting new work, the transport MUST deliver every unconsumed message that is not
  set aside, in recording order per FR-007.
- **FR-019**: Consumed messages MUST be kept for a configurable retention period (default 7 days) after their
  consumption, so they can be queried (FR-022), and then removed by the transport without operator action.
  Unconsumed and set-aside messages MUST never be removed by retention.
- **FR-020**: On graceful shutdown the transport MUST stop starting new deliveries and wait a bounded time for
  deliveries in progress; anything not finished stays unconsumed.
- **FR-021**: The transport is for a single running instance. It MUST NOT lose or skip a message if a second
  instance is started against the same log by mistake; any duplicate delivery that results is tolerated through
  the existing idempotency rules (005), and the second instance MUST log a warning that the setup is unsupported.

**Operations**

- **FR-022**: The transport MUST offer a read-only query of the store by status (unconsumed, consumed, set aside) and
  by saga instance, reporting the attributes in FR-003 plus status, attempt count and last failure.
- **FR-023**: An operator MUST be able to request that a set-aside message be delivered again; it then follows
  FR-008 and FR-014 to FR-016 like a new message.

**Fit with the existing model**

- **FR-024**: Using the transport MUST NOT require any change to saga, aggregate, command handler or event classes;
  the only annotation-model change is the opt-in declaration of FR-010, which is needed only for outbox commands.
- **FR-025**: The transport MUST plug into the engine only through SPI contracts in `saga-forge-core`
  (`EventDispatcher`, `Outbox` with `IN_TRANSACTION` timing, `EventReceiver`, `TransactionBoundary`, and those
  this feature adds); existing contracts MAY only be extended additively (research §2, §3). Any new contract it needs
  (the store itself) MUST be transport- and storage-agnostic and live in `saga-forge-core`, so that the Kafka
  transport can reuse it.
- **FR-026**: The store and the in-process relay MUST depend on no application framework (no Spring, no CDI, no
  Quarkus) and MUST be usable from plain Java by supplying a `TransactionBoundary`, a `DataSource`-backed log and an
  executor. Framework adapters (the Spring Boot starter; a later Quarkus extension) MUST only supply those from the
  host — its transaction manager, managed executor and start/stop lifecycle — and MUST NOT change delivery
  semantics. The same saga model and configuration values MUST give the same delivery behaviour under plain Java,
  Spring and Quarkus.
- **FR-027**: A framework adapter MAY additionally re-publish each delivered event on the host's own event
  mechanism (Spring application events; CDI events in Quarkus) so that non-saga listeners can observe it. Such
  re-publication MUST happen only after the event was delivered from the store, and a listener's failure MUST NOT
  affect the message's status.
- **FR-028**: When Spring Modulith's event publication registry is present in a Spring host, the transport MUST be
  able to use that registry as its message store instead of Saga Forge's own (interop mode), so the application has
  one outbox, not two. Interop mode MUST be chosen explicitly or by detection in the starter, never by
  `saga-forge-core` or the in-memory transport module itself, which stay unaware of Spring (Constitution
  Principle I). In interop mode, FR-001 to FR-023 MUST still hold.
- **FR-028a**: In interop mode the registry MUST hold the only copy of each message (payload and completion), and
  Saga Forge MUST keep the delivery bookkeeping the registry lacks — attempt count, last failure, set-aside status,
  the saga instance a message concerns and its recording position — in a side record keyed by the registry's
  publication identity, written in the same transaction as the registry entry. The same acceptance tests MUST pass
  in both modes; interop mode MUST NOT rely on registry behaviour beyond storing, completing and listing
  publications.
- **FR-029**: This feature MUST deliver (a) the framework-free transport and log (FR-026) and (b) the Spring wiring
  for this transport only — the host's transaction manager as `TransactionBoundary`, a managed executor, start/stop
  bound to the application lifecycle, and Spring Modulith interop mode (FR-028) — in the
  `saga-forge-spring-boot-starter` module. Detecting storage and transport on the classpath and assembling the
  engine (the starter's full autoconfiguration) and a Quarkus extension are out of scope; nothing in (a) MAY assume
  Spring, so that the Quarkus extension needs no change to it.

### Key Entities

- **Logged message**: one event or command that left a unit of work. Identity (unit of work id plus index), kind,
  type, payload, recording time, originating saga type and id (if any), status, attempt count, last failure,
  consumption time.
- **Message status**: `UNCONSUMED` (waiting or being retried), `CONSUMED`, `SET_ASIDE` (failed too often or
  unreadable; needs an operator).
- **Delivery attempt**: one try to hand a message to its consumer; its result (consumed, failed with a reason)
  updates the message.
- **Message store**: the ordered, durable collection of logged messages behind a storage-agnostic contract, stored
  alongside saga state by the configured storage provider — or, in Spring Modulith interop mode, by its event
  publication registry.
- **Delivery record** (interop mode only): Saga Forge's bookkeeping for one registry publication — publication
  identity, saga instance, recording position, attempt count, last failure, set-aside status.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: In a fault-injection suite that stops the process at every point between a unit of work's commit and
  the message's consumption, 100% of committed messages are delivered after restart, and no step runs twice.
- **SC-002**: 0 messages from rolled-back units of work are ever delivered, across the same suite.
- **SC-003**: A message recorded by a committed unit of work is delivered to its consumer within 100 ms in 99% of
  cases on an idle single-instance application.
- **SC-004**: The canonical `OrderProcessingSaga` runs end to end in a single process with the in-memory transport,
  with no broker and no change to its source.
- **SC-005**: After a restart with 10,000 unconsumed messages, all of them are delivered and the application is
  accepting new work within 60 seconds.
- **SC-006**: A message that always fails is set aside after the configured number of attempts, and messages of
  other saga instances keep being delivered meanwhile.
- **SC-007**: An operator can tell, for any saga instance, which of its messages are unconsumed, consumed or set
  aside, without reading storage tables.
- **SC-008**: The acceptance suite for User Stories 1 to 4 passes unchanged in three setups: plain Java with Saga
  Forge's own log, Spring with Saga Forge's own log, and Spring with Spring Modulith interop mode.

## Assumptions

- **Single instance**: the modulith runs one instance at a time against its datastore. Multi-instance delivery,
  partitioning and consumer groups belong to the Kafka transport.
- **Storage**: the store is implemented for Postgres in this feature (the only storage implemented today, 003), in
  the same database as saga state; MySQL and MongoDB implement it when those storage modules are built. The host
  applies the schema, as for 003 (G-003-25).
- **Outbox shape**: FR-001 is the transactional outbox G-ENG-07 anticipates: an `IN_TRANSACTION` `Outbox` writes
  the store entries, and an in-process relay delivers committed entries. The engine itself does not change.
- **Consumers**: the only event consumer is the saga engine (`EventReceiver`); non-saga event listeners (policies,
  projections) are out of scope.
- **Payload encoding**: messages are encoded by the storage module (as the Postgres module already does for JSONB
  state with Jackson), never in `saga-forge-core`. Event and command classes must be encodable; versioning of
  message types between releases is out of scope (an unreadable message is set aside).
- **Retry of deliveries** reuses the bounded, in-process waiting style of 005; long or scheduled backoff stays out
  of scope (G-005-01). Default wait between delivery attempts grows from 1 s to at most 1 min.
- **Start-event recovery**: a redelivered start event of an instance whose first unit of work never committed stays
  a duplicate start (G-ENG-05, G-005-06); fixing that rule is a separate decision.
- **Scope boundary** (Constitution Principle IV): this is the in-memory transport; no broker or other transport is
  added. The Kafka transport is a later feature that reuses the store vocabulary. Quarkus becomes an in-scope host
  framework by user decision (2026-11-11); the constitution (Principle IV, Technical Constraints) is amended through
  `/speckit-constitution` before planning, and the Quarkus extension itself is a later feature.
- **Spring and Quarkus**: most hosts will be Spring applications, and Quarkus must work too (FR-026).
  `docs/architecture.md` describes the in-memory transport as wrapping Spring's `ApplicationEventPublisher`; that
  is superseded by FR-026 to FR-028 and the doc is updated with this feature. Quarkus has no equivalent of Spring
  Modulith's event publication registry (only CDI events, transactional observers and the Vert.x event bus), so a
  Quarkus host always uses Saga Forge's own log.
