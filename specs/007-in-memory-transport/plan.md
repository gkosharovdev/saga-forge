# Implementation Plan: In-Memory Transport with a Durable Message Log

**Branch**: `007-in-memory-transport` | **Date**: 2026-11-11 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/007-in-memory-transport/spec.md`

## Summary

Deliver the first `EventDispatcher` implementation: an in-process transport for single-instance moduliths whose
messages survive a restart. Every event a committed unit of work hands off, every event published outside a unit of
work, and every command whose type opts in with `@Command(delivery = LOGGED)` is appended to a durable **message log**
in the same transaction as the saga state (a transactional outbox behind the 004 `IN_TRANSACTION` `Outbox`, called `EventHandoff` until this feature renames it). A relay in
the same process leases committed entries with a lease, delivers them to the engine's `EventReceiver` or the new
`CommandReceiver` in per-aggregate order on a small worker pool, marks them consumed, retries failures with bounded
backoff and sets aside entries that keep failing. The log contract lives in `saga-forge-core`; Postgres implements it.
The transport is framework-free; the Spring Boot starter gains only this transport's wiring, including an interop mode
that keeps the messages in Spring Modulith's event publication registry with Saga Forge's bookkeeping beside them.
Design detail: [research.md](research.md).

## Technical Context

**Language/Version**: Java 21 (root `maven.compiler.source/target` 21)

**Primary Dependencies**: core and transport: JDK only (core annotations/SPI). Postgres module: PostgreSQL JDBC driver,
Jackson 3 (`tools.jackson.core:jackson-databind`), as today. Starter: Spring Boot 4.1.2 (`spring-boot-autoconfigure`,
`spring-jdbc`/`spring-tx`), Spring Modulith 2.1.x `spring-modulith-events-core` as an optional dependency.

**Storage**: PostgreSQL — new table `saga_message_log` plus `saga_message_log_consumer` in `schema.sql`, with
`schema-upgrade-007.sql` for existing databases. Interop mode: Spring Modulith's publication table in the same database.

**Testing**: JUnit 5, AssertJ, jqwik (Property 35), Testcontainers Postgres for container suites (`@Tag("container")`,
`-Pcontainer-tests`), Spring Boot test support for the starter.

**Target Platform**: JVM library; plain Java, Spring Boot 4.1 hosts now; Quarkus hosts later (G-007-01).

**Project Type**: multi-module Maven library (framework).

**Performance Goals**: SC-003 — delivery within 100 ms p99 after commit on an idle instance (signal after commit, 500 ms
fallback poll); SC-005 — 10,000 unconsumed entries delivered within 60 s of restart (batched leases of 100).

**Constraints**: zero dependencies in core and the transport module (Principle I); at-least-once delivery; no
long/scheduled backoff beyond 1 min (G-005-01 style); single running instance (FR-021).

**Scale/Scope**: one instance per datastore; log volume bounded by 7-day retention of consumed entries.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Check | Result |
|---|---|---|
| I. Zero-Dependency Core | Core gains only an enum attribute, the `MessageLog` contract and records, `CommandReceiver`, and additive changes to `Outbox`/`OutgoingMessages`; JDK only. The transport module depends on core only. Spring and Modulith types stay in the starter. | Pass |
| II. Annotation-Driven Declarative Style | Opt-in is a framework-owned attribute `@Command(delivery = LOGGED)`, not a third-party annotation or orchestration code. | Pass |
| III. Formal Spec Conformance | R23 and Property 35 are added to `.kiro/specs/saga-forge-framework/design.md` before the scanner and engine change; Speckit flow followed. | Pass (task ordering) |
| IV. Explicit, Narrow Scope | Transport is in-memory (in scope); storage is Postgres (in scope). Spring Modulith interop is neither a storage nor a transport — the registry lives in the same Postgres database — see Complexity Tracking. Quarkus is not delivered here; listing it as an in-scope host awaits a constitution amendment (G-007-01). | Pass, justified |
| V. Documentation Hierarchy | `docs/architecture.md` (module responsibilities, transport row) is updated: the in-memory transport no longer wraps `ApplicationEventPublisher`. AGENTS.md "Current Status" updated at the end. | Pass (task) |

**Post-design re-check (after Phase 1)**: unchanged — the contracts add no dependency to core or the transport, the
opt-in stays a framework-owned attribute, and Spring/Modulith types appear only in `contracts/spring-wiring.md`.
New gaps G-007-04 to G-007-06 recorded in `.specify/memory/memory.md`.

## Project Structure

### Documentation (this feature)

```text
specs/007-in-memory-transport/
├── plan.md              # This file
├── research.md          # Phase 0: decisions §1–§13, risks R1–R3
├── data-model.md        # Phase 1: entities, table, state machine
├── quickstart.md        # Phase 1: validation scenarios
├── contracts/
│   ├── core-spi.md              # MessageLog, CommandReceiver, Outbox.committed, OutgoingMessages, @Command
│   ├── in-memory-transport.md   # InMemoryTransport public surface and behaviour
│   ├── postgres-message-log.md  # PostgresMessageLog and schema
│   └── spring-wiring.md         # Starter: SpringTransactionBoundary, lifecycle, bridge, Modulith interop
└── tasks.md             # Phase 2 (/speckit-tasks)
```

### Source Code (repository root)

```text
saga-forge-core/src/main/java/io/github/gkosharovdev/sagaforge/core/
├── annotation/Command.java, CommandDelivery.java          # delivery attribute (R23)
├── spi/Outbox.java, OutgoingMessages.java,
│   OutgoingCommand.java, CommandReceiver.java, LoggedCommand.java
├── spi/log/                                               # new: MessageLog, LogEntry, LoggedMessage, MessageKind,
│                                                          #      MessageStatus, MessageQuery, DeliveryFailure, ...
└── scanner/ReflectiveAnnotationScanner.java               # R23 in validateAll

saga-forge-engine/src/main/java/io/github/gkosharovdev/sagaforge/engine/
├── SagaEngine.java                                        # commandReceiver()
└── internal/EngineCommandGateway.java, UnitOfWork.java,   # buffer LOGGED commands, outgoing batch, committed hook,
    NonSagaUnitOfWork.java, SagaUnitOfWork.java, ...       # CommandReceiver implementation

saga-forge-transport-inmemory/src/main/java/io/github/gkosharovdev/sagaforge/transport/inmemory/
├── InMemoryTransport.java (+ Builder), TransportSettings.java, MessageLogQueries.java
└── internal/ Relay.java, Worker pool, OrderingKeys.java, Backoff.java, RetentionSweeper.java, Heartbeat.java
saga-forge-transport-inmemory/src/test/...                 # test-jar: InMemoryMessageLog double

saga-forge-storage-postgres/src/main/java/.../storage/postgres/
├── PostgresMessageLog.java, MessageLogRows.java
└── resources: schema.sql (extended), schema-upgrade-007.sql

saga-forge-spring-boot-starter/src/main/java/io/github/gkosharovdev/sagaforge/spring/
├── SpringTransactionBoundary.java
├── transport/InMemoryTransportConfiguration.java, TransportLifecycle.java, ApplicationEventBridge.java
└── modulith/ModulithMessageLog.java, SagaForgeEnvelope.java, ModulithInteropConfiguration.java

saga-forge-example/                                         # canonical saga run end to end over the transport (SC-004)
```

**Structure Decision**: existing multi-module layout; code lands in the placeholder modules
`saga-forge-transport-inmemory` and `saga-forge-spring-boot-starter`, plus additive changes in core, engine and the
Postgres module. Package names follow the existing `io.github.gkosharovdev.sagaforge.<module>` scheme.

## Phases (for /speckit-tasks)

0. **Rename the handoff SPI** to outbox vocabulary (research §14): core, engine, test doubles, docs, `.kiro` design
   docs, plus a mapping note in `specs/004-saga-engine`. Pure rename; all existing tests stay green.
1. **Formal spec first**: R23 and Property 35 in `.kiro/specs/saga-forge-framework/design.md`.
2. **Core contracts**: `CommandDelivery`, `OutgoingCommand`, `OutgoingMessages.commands`, `Outbox.committed`,
   `CommandReceiver`/`LoggedCommand`, `core.spi.log` (contracts/core-spi.md); R23 in the scanner.
3. **Engine**: logged commands buffered and passed to the outbox; `CommandReceiver`; `committed` hook; Property 35 tests.
4. **Transport** over the in-memory log double: append paths, relay, ordering, retries, set-aside, restart, shutdown,
   retention, heartbeat, queries (User Stories 1–4 at unit level).
5. **Postgres log**: `PostgresMessageLog`, schema and upgrade script, container contract suite, fault injection
   (SC-001/002), 10k restart (SC-005).
6. **Spring wiring**: boundary, lifecycle, executor, event bridge; Modulith interop starting with the R1 spike; SC-008
   three-setup acceptance suite.
7. **Example and docs**: example over the transport (SC-004); `docs/architecture.md`, README, AGENTS.md, memory.md.

## Complexity Tracking

| Item | Why Needed | Simpler Alternative Rejected Because |
|---|---|---|
| Spring Modulith interop (new optional dependency in the starter) | User decision (Clarifications): a Spring Modulith application must have one outbox, not two (FR-028). It adds no storage or transport technology — the registry is a table in the same Postgres database. | Ignoring Modulith leaves two outboxes; delivering through Modulith listeners cannot meet FR-009/FR-016 (research §10). |
| Renaming the 004 handoff SPI (`EventHandoff` → `Outbox`, research §14) | User decision, 2026-11-11: "handoff" is not recognisable messaging vocabulary, and the SPI now carries commands. | An alias keeps two names for one SPI. |
| Additive changes to `Outbox` and `OutgoingMessages` | Logged commands and a post-commit signal need them (research §2, §3); FR-025 reworded accordingly. | `LocalTransaction.afterCommit` touches every boundary implementation; a second outbox SPI doubles the call sites. |
