# Implementation Plan: Process Metadata Propagation

**Branch**: `019-process-metadata` | **Date**: 2026-11-27 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/019-process-metadata/spec.md`

## Summary

Saga Forge gains process metadata: an immutable, bounded string map (`core.metadata.SagaMetadata`) captured from the
host's thread context by a host SPI (`core.spi.MetadataAccessor`, capture and restore with a scope), written on the
instance at its creation write, recorded on every lifecycle entry and carried on every stored message next to its trace
context. A transport hands it to the runtime explicitly through a new `EventDelivery` parameter object; the runtime
restores it around each whole unit of work, and the relay around each delivery. Saga code reads it through an optional
`SagaMetadata` field on the saga and a `SagaMetadata` parameter on command handlers (D7). Outcome events are unchanged:
their message carries the metadata (D6). Postgres stores three new JSONB columns in migration `V6`. The starter adds
an SLF4J MDC accessor for the keys in `saga-forge.metadata.mdc-keys`, shows metadata in the `sagas` endpoint and the
accessor in `sagaforge`. The fixture lets tests hand over and expect metadata. Details: [research.md](research.md).

## Technical Context

**Language/Version**: Java 21 (`maven.compiler.release` 21; CI also on JDK 25)

**Primary Dependencies**: core none (JDK only); runtime and transport unchanged (`opentelemetry-api` 1.62.0); storage
Jackson 3 and the Postgres driver; starter Spring Boot 4.1, `slf4j-api` as a new optional dependency

**Storage**: Postgres (`saga_instance`, `saga_lifecycle_history`, `saga_message_store` gain `metadata JSONB`, migration
`V6__process_metadata`); in-memory doubles in test jars and the fixture

**Testing**: JUnit 5, AssertJ, ArchUnit (`UnitOfWorkBoundaryTest`), Testcontainers behind `@Tag("container")` /
`-Pcontainer-tests`; contract tests `MessageStoreContract`, `TransportAcceptanceSuite`, new `MetadataAccessorContract`

**Target Platform**: JVM library; Spring Boot applications through the starter

**Project Type**: multi-module Maven library

**Performance Goals**: no measurable overhead with `MetadataAccessor.NONE` (one empty-map check per unit of work);
with an accessor, one capture per produced-outside message and one restore per unit of work and per delivery

**Constraints**: core zero dependencies (Principle I); metadata bounded (32 entries, 128/1,024 characters); never a
span or metric attribute; an accessor failure never fails a unit of work or request; old public constructors kept

**Scale/Scope**: 7 modules touched (core, runtime, transport, storage-postgres, starter, test, three examples), about
25 production types changed, 6 new

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Check | Result |
|---|---|---|
| I. Zero-Dependency Core | `SagaMetadata`, `MetadataAccessor`, `EventDelivery` are plain JDK types; MDC lives only in the starter (`MdcMetadataAccessor`), SLF4J optional there | Pass |
| II. Annotation-Driven, Declarative | Reading is a type-recognised saga field and an engine-supplied handler parameter, the same mechanisms as `SagaContext`/`CommandGateway`/`CommandId`; no imperative orchestration, no third-party alias | Pass |
| III. Formal Spec Conformance | New scanner rules R32/R33 and Property 50 added to `.kiro/specs/saga-forge-framework/design.md`; Property 38 (outcome events) and R5/R21/Property 10 unchanged; Speckit flow followed | Pass |
| IV. Explicit, Narrow Scope | No new storage or transport; Postgres and in-memory only; Kafka obligation recorded as a gap | Pass |
| V. Documentation Hierarchy | Guide page, architecture, use-cases, README of the starter, agent docs and `llms-full.txt` updated; DDD vocabulary unchanged | Pass |
| Workflow: composition over inheritance | No new class hierarchy; `Scope` is an interface implemented by small private classes | Pass |
| Workflow: test an abstraction first | `MetadataAccessor` gets `MetadataAccessorContract` run against a thread-local double and the MDC accessor; `EventDelivery` is exercised by the relay, the fixture's loop-back and the runtime | Pass |
| Workflow: familiar abstractions | `MetadataAccessor`+`Scope`: modelled on Micrometer Context Propagation's `ThreadLocalAccessor` and OpenTelemetry's `Scope` (high familiarity); `EventDelivery`: parameter object, "delivery" is common messaging vocabulary (high); `SagaMetadata`: "metadata" as in Axon `MetaData`, message headers (high) | Pass |
| Design skills | `effective-java` (Items 1, 17, 49, 51) and `solid-design-review` applied: one value type with strict and lenient factories, a parameter object instead of a third overload, a two-method SPI kept together because both methods share the key set (ISP considered, R1) | Pass |

**Post-design re-check (after Phase 1)**: unchanged, all Pass. No complexity tracking needed.

## Project Structure

### Documentation (this feature)

```text
specs/019-process-metadata/
├── spec.md
├── plan.md                  # this file
├── research.md              # R1 to R13
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── metadata-spi.md
│   ├── propagation.md
│   ├── saga-code.md
│   ├── storage-and-operations.md
│   └── starter-and-fixture.md
├── checklists/requirements.md
└── tasks.md                 # /speckit-tasks
```

### Source Code (repository root)

```text
saga-forge-core/src/main/java/io/github/gkosharovdev/sagaforge/core/
├── metadata/SagaMetadata.java                     # new
├── spi/MetadataAccessor.java                      # new
├── spi/EventDelivery.java                         # new
├── spi/EventReceiver.java                         # receive(EventDelivery)
├── spi/SagaInstance.java, SagaLifecycleEntry.java, OutgoingMessages.java, OutboxCommand.java
├── spi/store/NewMessage.java, StoredMessage.java
└── scanner/ReflectiveAnnotationScanner.java       # R32, R33
saga-forge-core/src/test/.../core/spi/MetadataAccessorContract.java + ThreadLocalMetadataAccessor.java (test-jar)

saga-forge-runtime/src/main/java/io/github/gkosharovdev/sagaforge/runtime/
├── SagaRuntime.java                               # Builder.metadataAccessor
└── internal/EngineSettings, EventRouter, UnitOfWork, WorkContext, SagaModel, EngineCommandGateway,
    EngineSagaLifecycle, HandleCommand, Diagnostics, MetadataScopes (new, package-private)

saga-forge-transport-inmemory/src/main/java/.../transport/inmemory/
├── InMemoryTransport.java                         # Builder.metadataAccessor
└── internal/LogAppender.java, Relay.java

saga-forge-storage-postgres/src/main/
├── java/.../storage/postgres/PostgresStorageProvider, PostgresMessageStore, JsonCodec, PostgresSchema
└── resources/.../storage/postgres/migration/V6__process_metadata.sql, schema.sql, schema-upgrade-019.sql,
    schema-levels/V6.json

saga-forge-spring-boot-starter/src/main/java/.../spring/
├── metadata/SagaForgeMetadataConfiguration.java, MdcMetadataAccessor.java   # new
├── SagaForgeProperties (Metadata), SagaRuntimeConfiguration, InMemoryTransportConfiguration,
    InboundSagaEventListener wiring, SagasEndpoint, sagaforge endpoint
└── README.md

saga-forge-test/src/main/java/.../test/SagaFixture, Given, Then, InstanceView, RecordedCommand, LoopBackTransport,
    FixtureStorage

saga-forge-example/…/OrderProcessingSagaFixtureTest (metadata case)
saga-forge-example-spring/…/MetadataContainerTest (new), application properties
docs/guide/metadata.md (new), docs/architecture.md, docs/agents/*, llms-full.txt, .specify/memory/memory.md, AGENTS.md
```

**Structure Decision**: the existing multi-module layout; one new core package `core.metadata` (the value is used by
saga code, so it sits beside `core.lifecycle` rather than in `core.spi`), and one new starter package
`spring.metadata`.

## Phases

- **Phase 0 (done)**: [research.md](research.md) R1 to R13; no open NEEDS CLARIFICATION.
- **Phase 1 (done)**: [data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md).
- **Phase 2**: `/speckit-tasks`. Suggested order: core value and SPI with contract test → scanner rules → messages and
  instance records → runtime (entry, creation write, unit-of-work scope, injection, handler parameter, history) →
  transport → Postgres V6 → fixture → starter → examples → docs and memory.md.

## Spec adjustments made while planning

- FR-022 narrowed: the fixture exposes metadata on instances and recorded commands; outcome-message metadata is covered
  by runtime tests, because the fixture collects outcome events, not their messages (R11, G-011-07).
- FR-013a's rule is numbered R33 (R32 is the field rule).

## Complexity Tracking

None.
