---

description: "Task list for 019-process-metadata"
---

# Tasks: Process Metadata Propagation

**Input**: Design documents from `/specs/019-process-metadata/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md),
[contracts/](contracts/), [quickstart.md](quickstart.md)

**Tests**: included. Every story has an independent test, the quickstart and the contracts name the suites, and
AGENTS.md asks for a test with every new abstraction, written with its first caller.

**Organization**: the tasks run in this order.
1. Setup.
2. The foundational phase: the value, the SPI and its contract test, the changed records, the scanner rules, the
   runtime's capture, injection and restoration, the transport's hop and Postgres `V6`. Every story needs these:
   US1's restart test needs the stored metadata, US2 reads it, US3 and US4 run over the runtime.
3. The stories in spec order:
   - US1, the starter's MDC accessor and the Spring end-to-end proof;
   - US2, metadata in the operations API and the `sagas`/`sagaforge` endpoints;
   - US3, a host without Spring: no-accessor behaviour, failure tolerance, a plain-Java example;
   - US4, the test fixture.
4. Docs and register, then verification.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an unfinished task)
- **[Story]**: the user story from spec.md (US1 to US4)

## Path Conventions

| Abbreviation | Path |
|---|---|
| `core…` | `saga-forge-core/src/main/java/io/github/gkosharovdev/sagaforge/core` |
| `core-test…` | `saga-forge-core/src/test/java/io/github/gkosharovdev/sagaforge/core` |
| `rt…` | `saga-forge-runtime/src/main/java/io/github/gkosharovdev/sagaforge/runtime` |
| `rt-test…` | `saga-forge-runtime/src/test/java/io/github/gkosharovdev/sagaforge/runtime` |
| `tr…` | `saga-forge-transport-inmemory/src/main/java/io/github/gkosharovdev/sagaforge/transport/inmemory` |
| `tr-test…` | `saga-forge-transport-inmemory/src/test/java/io/github/gkosharovdev/sagaforge/transport/inmemory` |
| `pg…` | `saga-forge-storage-postgres/src/main/java/io/github/gkosharovdev/sagaforge/storage/postgres` |
| `pg-res…` | `saga-forge-storage-postgres/src/main/resources/io/github/gkosharovdev/sagaforge/storage/postgres` |
| `pg-test…` | `saga-forge-storage-postgres/src/test/java/io/github/gkosharovdev/sagaforge/storage/postgres` |
| `st…` | `saga-forge-spring-boot-starter/src/main/java/io/github/gkosharovdev/sagaforge/spring` |
| `st-test…` | `saga-forge-spring-boot-starter/src/test/java/io/github/gkosharovdev/sagaforge/spring` |
| `fx…` | `saga-forge-test/src/main/java/io/github/gkosharovdev/sagaforge/test` |
| `fx-test…` | `saga-forge-test/src/test/java/io/github/gkosharovdev/sagaforge/test` |
| `ex-spring…` | `saga-forge-example-spring/src/test/java/io/github/gkosharovdev/sagaforge/example/spring` |

Container tests carry `@Tag("container")` and use `postgres:16-alpine`, like the existing suites. Every changed record
keeps its existing public constructors, which pass `SagaMetadata.empty()` (data-model.md).

---

## Phase 1: Setup

**Purpose**: a green baseline and the design skills.

- [X] T001 On branch `019-process-metadata` (from `master` at the 018 merge), run `mvn -q install` from the repository root and confirm it is green before any change
- [X] T002 Load the `effective-java`, `clean-code-java` and `solid-design-review` skills (AGENTS.md) and keep them applied to every type added below; read `specs/019-process-metadata/research.md` R1–R13 and the five files in `specs/019-process-metadata/contracts/`

---

## Phase 2: Foundational (blocking prerequisites)

**Purpose**: metadata exists, travels, is restored, is readable and is stored. All stories build on it.

### Value and SPI (contracts/metadata-spi.md, research R1–R2)

- [X] T003 Create `core…/metadata/SagaMetadata.java` (public record, key-ordered unmodifiable copy) with `MAX_ENTRIES = 32`, `MAX_KEY_LENGTH = 128`, `MAX_VALUE_LENGTH = 1024`, `empty()`, strict `of(Map)`, lenient `sanitized(Map, Consumer<String> droppedKey)`, `get(String)` → `Optional<String>`, `isEmpty()`, `asMap()`; the canonical constructor applies `of`'s rules; add `core…/metadata/package-info.java`. Javadoc per the release build's rules (no `<h3>` in type comments, no `@throws` on record comments)
- [X] T004 [P] Add `core-test…/metadata/SagaMetadataTest.java` covering every row of the table in contracts/metadata-spi.md (null map, blank and null keys, null values, over-long keys and values, 33 entries kept to the first 32 in key order, `droppedKey` called once per key, equality and ordering)
- [X] T005 Create `core…/spi/MetadataAccessor.java` with `capture()`, `restore(SagaMetadata)` → nested `Scope extends AutoCloseable` (`close()` without checked exception) and the constant `NONE` (empty capture, no-op scope); Javadoc states thread confinement, nesting and the callers' failure handling (contracts/metadata-spi.md)
- [X] T006 Create the test-jar contract `core-test…/spi/MetadataAccessorContract.java` (abstract JUnit class: capture after restore returns the restored keys; close puts back the previous state, including absent keys; nested scopes unwind in order; `NONE` behaves as specified) and the double `core-test…/spi/ThreadLocalMetadataAccessor.java`; add `ThreadLocalMetadataAccessorContractTest` and confirm `saga-forge-core`'s test-jar exports both

### Records and receiver (contracts/propagation.md, research R3)

- [X] T007 Create `core…/spi/EventDelivery.java` (record `event`, nullable non-blank `sourceSagaId`, `metadata` with `null` → `empty()`) and add the default `receive(EventDelivery)` to `core…/spi/EventReceiver.java` (delegates to `receive(event, sourceSagaId)` or `receive(event)`; Javadoc says decorators must forward it, G-019-04); add `core-test…/spi/EventReceiverDefaultsTest.java` for the delegation
- [X] T008 [P] Add the `SagaMetadata metadata` component (last) to `core…/spi/store/NewMessage.java` and `core…/spi/store/StoredMessage.java` next to `traceContext` (`null` → `empty()`), keeping every existing constructor; update their tests in `core-test…/spi/store/`
- [X] T009 [P] Add `SagaMetadata metadata` to `core…/spi/OutgoingMessages.java` and `core…/spi/OutboxCommand.java`, keeping existing constructors; update `core-test…/spi/OutgoingMessagesTest.java` (or the nearest existing test)
- [X] T010 [P] Add `SagaMetadata metadata` (last) and `withMetadata(SagaMetadata)` to `core…/spi/SagaInstance.java`, carry it through every existing `with…` method, keep the four existing constructors; add `SagaMetadata metadata` (last) to `core…/spi/SagaLifecycleEntry.java` with its old constructor kept; update `core-test…/spi/SagaInstanceTest.java` and `SagaLifecycleEntryTest.java`
- [X] T011 Fix every compile break across the build caused by T008–T010 by using the old constructors (no behaviour change); run `mvn -q install -DskipTests` and then `mvn -q test` and confirm the suite is green with metadata always empty (SC-003 baseline)

### Scanner rules and Property 50 (contracts/saga-code.md)

- [X] T012 In `core…/scanner/ReflectiveAnnotationScanner.java` add R32 (at most one `SagaMetadata` field on a `@Saga` class, in `validate` and `validateAll`) and R33 (at most one `SagaMetadata` parameter per `@CommandHandler`, host rule in `validateAll` only) with the exact texts of contracts/saga-code.md
- [X] T013 [P] Add scanner tests for R32 and R33 in `core-test…/scanner/` (valid and invalid cases; text asserted) and add R32, R33 and Property 50 to `.kiro/specs/saga-forge-framework/design.md` (rules table and the paragraph saying where each rule runs)

### Runtime (contracts/propagation.md, research R4–R7)

- [X] T014 Add `metadataAccessor(MetadataAccessor)` (default `NONE`) to `SagaRuntime.Builder` in `rt…/SagaRuntime.java` and hold it in `rt…/internal/EngineSettings.java`; add a package-private `rt…/internal/MetadataScopes.java` that sanitises captures (diagnostic `saga.metadata.entry.dropped` naming the key), catches capture failures (`saga.metadata.capture.failed`, empty result) and opens/closes restore scopes catching failures (`saga.metadata.restore.failed`); add the three diagnostic events to `rt…/internal/Diagnostics.java` (keys only, never values, FR-024)
- [X] T015 In `rt…/internal/EventRouter.java` (and `EngineRuntime`/`SagaRuntime` where `EventReceiver` is implemented) make `receive(EventDelivery)` the main entry; `receive(event)` and `receive(event, source)` build an `EventDelivery` from `MetadataScopes` capture; keep each queued event's delivery metadata with it; write the start delivery's metadata on the creation write; adoption (015) and duplicate starts keep the stored metadata
- [X] T016 In `rt…/internal/UnitOfWork.java` open the restore scope around the whole run (every `AttemptLoop` attempt included) and close it in `finally`; instance scopes restore the instance's metadata read under the lock, standalone scopes the metadata of their work; set `OutgoingMessages.metadata` to the unit of work's metadata; carry it in `rt…/internal/WorkContext.java`. Keep `UnitOfWorkBoundaryTest` green
- [X] T017 [P] In `rt…/internal/SagaModel.java` find the optional `SagaMetadata` field (`Reflection.fieldOfType`) and inject it from `WorkContext.newSagaObject()` through `Injection` (read-only, never persisted)
- [X] T018 [P] In `rt…/internal/EngineCommandGateway.java` supply a `SagaMetadata` handler parameter beside `CommandId` with the handling unit of work's metadata (never asking `HandlerParameterResolver`); a `send` outside any unit of work captures through `MetadataScopes` before its standalone unit of work; `rt…/internal/HandleCommand.java` runs with the `OutboxCommand`'s metadata
- [X] T019 In `rt…/internal/EngineSagaLifecycle.java` (and the works that write entries: `RunSteps`, `StartCompensation`, `CompensationRun`, `RecordFailure`, `DeadlineDelivery`, `CommandFailureHandler`, the 015 repair works, `RecoverySweeper`) write entry metadata per the history table in contracts/propagation.md
- [X] T020 Add `rt-test…/internal/MetadataPropagationTest.java` over the runtime test doubles with `ThreadLocalMetadataAccessor`: start with `c-1`; a later event with `c-2` (instance stays `c-1`, entry records `c-2`); in-transaction and outbox commands (handler parameter); retry; `@StartCompensation`, failed outbox command (012) and deadline (017) compensations; operator resume and recovery sweep (015); fan-out start (014); recorded events seen by a recorded-event listener inside the scope; the thread's previous accessor state after each unit of work, also on failure (User Story 1 scenarios 1–5 at runtime level)
- [X] T021 [P] Add `rt-test…/internal/MetadataProperty50Test.java` for Property 50 (b)–(d): saga field, handler parameter (all three origins) and every `OutgoingMessages` entry carry the unit of work's metadata, with `MetadataAccessor.NONE` and with the thread-local double

### Transport (research R5, R7)

- [X] T022 Add `metadataAccessor(MetadataAccessor)` (default `NONE`) to `InMemoryTransport.Builder` in `tr…/InMemoryTransport.java`; in `tr…/internal/LogAppender.java` give every batch entry `batch.metadata()` and `published(...)` the sanitised capture
- [X] T023 In `tr…/internal/Relay.java` restore `message.metadata()` around each delivery (event, outcome event, command, scheduled message) and call `receive(new EventDelivery(event, source, metadata))`; build `OutboxCommand` with the metadata; nothing beyond the relay scope for ignored or duplicate deliveries
- [X] T024 [P] Extend the test-jar `InMemoryMessageStore` and `MessageStoreContract` (`tr-test…`) so metadata round-trips on append, lease and queries, and add `TransportAcceptanceSuite` cases: metadata on events, commands, scheduled messages and published events; restored around delivery; preserved across a relay restart over the same store

### Postgres V6 (contracts/storage-and-operations.md, research R8–R9)

- [X] T025 Add `pg-res…/migration/V6__process_metadata.sql` (three `ALTER TABLE … ADD COLUMN metadata JSONB NOT NULL DEFAULT '{}'::jsonb`), the same columns in `pg-res…/schema.sql`, and `pg-res…/schema-upgrade-019.sql` with `ADD COLUMN IF NOT EXISTS`; add version 6 to `pg…/PostgresSchema.java`; do not touch V1–V5 or `released-migrations.sha256`
- [X] T026 Regenerate `pg-res…/schema-levels/V6.json` with `mvn -pl saga-forge-storage-postgres -Pcontainer-tests test -Dtest=SchemaSnapshotTest -Dsaga-forge.write-snapshots=true` and update the migration equivalence tests (`MigrationResourcesTest` and the schema/upgrade equivalence suites) for V6
- [X] T027 Persist and read metadata in `pg…/PostgresStorageProvider.java` (instance and each lifecycle entry) and `pg…/PostgresMessageStore.java` (append, lease, queries, `appendDeliveryRecord`), encoding through `pg…/JsonCodec.java` as a JSON object of strings (`NULL`/`{}` → empty)
- [X] T028 [P] Add container tests in `pg-test…/`: round trip of the three columns; a V5 database migrated to V6 and one upgraded by `schema-upgrade-019.sql` read old rows as empty (SC-004); `PostgresSchemaValidator` names a missing `metadata` column; run `MessageStoreContract` for Postgres with the new cases

**Checkpoint**: metadata is captured, stored, carried, restored and readable without Spring. User stories can start.

---

## Phase 3: User Story 1 - A request's correlation id follows its saga everywhere (Priority: P1) 🎯 MVP

**Goal**: with `saga-forge.metadata.mdc-keys=correlationId`, every step, handler, compensation and deadline step of a
saga runs with the starting request's `correlationId` in MDC, across a relay hop and a restart.

**Independent Test**: `MetadataContainerTest` in the Spring example (quickstart §6).

- [X] T029 [US1] Add `org.slf4j:slf4j-api` as `<optional>true</optional>` to `saga-forge-spring-boot-starter/pom.xml` (version from Spring Boot's BOM) and `Metadata` (`mdcKeys: List<String>`, default empty) to `st…/SagaForgeProperties.java` with configuration metadata
- [X] T030 [US1] Create `st…/metadata/MdcMetadataAccessor.java` (package-private; behaviour table in contracts/starter-and-fixture.md) and `st…/metadata/SagaForgeMetadataConfiguration.java` (`@ConditionalOnClass(MDC.class)`, registers the accessor only when keys are configured, `@ConditionalOnMissingBean(MetadataAccessor.class)`; a startup warning when keys are set but SLF4J is absent); register it in the auto-configuration imports
- [X] T031 [US1] Hand the `MetadataAccessor` bean (or `NONE`) to the runtime in `st…/SagaRuntimeConfiguration.java`, to the transport in `st…/InMemoryTransportConfiguration.java`, and make the 014 inbound route (`InboundSagaEventListener`) and `ApplicationEventPublishingEventPublisher` capture on the publishing thread through the transport/runtime entry points
- [X] T032 [P] [US1] Add `st-test…/metadata/MdcMetadataAccessorContractTest.java` (extends core's `MetadataAccessorContract`; also: unconfigured keys are not written to MDC, absent keys are not captured) and `st-test…/metadata/SagaForgeMetadataConfigurationTest.java` with `ApplicationContextRunner` (no keys → no bean; keys → MDC accessor; application bean wins)
- [X] T033 [US1] Configure `saga-forge.metadata.mdc-keys=correlationId` in `saga-forge-example-spring`'s application properties and add `ex-spring…/MetadataContainerTest.java` (container): put `correlationId=c-1` in MDC, place an order through `OrderPlacement`, capture MDC inside the saga's steps, `PaymentService`'s handler (outbox hop) and the refund compensation, stop and restart the application context between two steps, and assert `c-1` in every capture and the thread's previous MDC after each (SC-001) — as built: the example has no outbox command, so the hop is the stored start event, delivered by a second context after the first stopped; MDC captured in `PaymentProvider` (charge from the start step's handler, refund from the compensation) (memory.md G-019-08)

**Checkpoint**: User Story 1 works end to end in Spring.

---

## Phase 4: User Story 2 - An operator sees which request a saga belongs to (Priority: P1)

**Goal**: the operations API and the `sagas` endpoint show the instance's metadata and each history entry's metadata;
the `sagaforge` endpoint shows the active accessor.

**Independent Test**: start a saga over Postgres with `{correlationId=c-1, tenant=t-9}`, deliver a later event with
`{correlationId=c-2}`, read instance and history through `SagaOperations` and the endpoint.

- [X] T034 [US2] Add `rt-test…/internal/OperationsMetadataTest.java`: `find`, `findByAssociation`, `list` and `history` return the metadata written in Phase 2 (instance `c-1`, later entry `c-2`; operator entries carry the instance's)
- [X] T035 [US2] Add `metadata` to the instance and history views of `st…/…/SagasEndpoint.java` (always shown, D10) and `metadata: {accessor, mdcKeys}` to the `sagaforge` endpoint (`"none"` when no accessor); update their tests in `st-test…`
- [X] T036 [P] [US2] Extend `ex-spring…/SagasEndpointWebContainerTest.java` (container) to assert the instance's and an entry's `metadata`, and that an instance written before the upgrade (inserted with empty metadata) renders `{}`

**Checkpoint**: operators can tell which request started an instance and which message triggered each step (SC-002).

---

## Phase 5: User Story 3 - A host without Spring plugs in its own context (Priority: P2)

**Goal**: hand-assembled hosts register their own accessor; no accessor changes nothing; a failing accessor never fails
work.

**Independent Test**: runtime and transport assembled by hand with the thread-local double (quickstart §2).

- [X] T037 [US3] Add `rt-test…/internal/MetadataFaultToleranceTest.java`: an accessor that throws in `capture` (instance starts empty, one diagnostic), in `restore` and in `close` (work runs and commits, diagnostic reported once per unit of work); over-bound entries dropped with a key-only diagnostic (SC-005)
- [X] T038 [P] [US3] Add a plain-Java assembly test in `saga-forge-example` (beside the existing hand-assembled runtime tests) that wires `SagaRuntime` and `InMemoryTransport` with one accessor and shows the metadata reaching a step and an outbox-command handler (as built: `checkout/CheckoutMetadataTest`, Logistics' `RequestShipmentCommand` handler takes `SagaMetadata`); confirm `mvn -pl saga-forge-core,saga-forge-runtime dependency:tree` shows no new dependency (SC-006)

---

## Phase 6: User Story 4 - A saga test states and checks metadata (Priority: P2)

**Goal**: fixture tests hand over and expect metadata.

**Independent Test**: `OrderProcessingSagaFixtureTest` metadata case (quickstart §5).

- [X] T039 [US4] In `fx…/SagaFixture.java` and `fx…/Given.java` add `when(Object, SagaMetadata)` and `given(SagaMetadata, Object...)`; add `Builder.metadataAccessor(MetadataAccessor)`; make `fx…/LoopBackTransport.java` deliver with `EventDelivery` and keep metadata on its stored messages and in `FixtureStorage`
- [X] T040 [US4] Add `metadata()` to `fx…/InstanceView.java` and `fx…/RecordedCommand.java`, and `expectMetadata(SagaMetadata)` to `fx…/Then.java` (failure lists expected and actual entries); add `fx-test…/FixtureMetadataTest.java`; extend `FixtureParityTest` so fixture and hand-assembled runtime agree on metadata
- [X] T041 [P] [US4] Add a metadata case to `saga-forge-example`'s `OrderProcessingSagaFixtureTest` (saga declares a `SagaMetadata` field and a handler a `SagaMetadata` parameter, per contracts/saga-code.md) (as built: the handler parameter is on the checkout's `LogisticsService`, tested in `CheckoutAndFulfilmentTest`; the canonical saga has no use for a field, so its case checks `expectMetadata`) and the matching case to `saga-forge-example-spring`'s `OrderSagaFixtureTest`; keep `saga-forge-example-jmolecules` in step if it shares the saga shape

---

## Phase 7: Polish & Cross-Cutting Concerns

- [X] T042 [P] Write `docs/guide/metadata.md` (what metadata is, the MDC property, a custom accessor, reading in a saga and a handler, limits, what is not propagated) with snippets copied from compiling code naming their source files; add it to `mkdocs.yml` and add R32/R33 texts to the guide's error table and limitations (`docs/guide/limitations.md`, G-019-01 to G-019-06)
- [X] T043 [P] Update `docs/architecture.md` (metadata beside trace context, `EventDelivery`, restoration scopes) and `docs/use-cases.md` §1 (a correlation id in the checkout story)
- [X] T044 [P] Update the starter's `README.md` with `saga-forge.metadata.mdc-keys` and the endpoint fields; keep `ReadmePropertiesTest` green
- [X] T045 [P] Update `docs/agents/saga-forge/SKILL.md` and `docs/agents/AGENTS-snippet.md`, then run `docs/guide/build-llms-full.sh`
- [X] T046 Update `.specify/memory/memory.md` (G-ADOPT-05 closed by 019; G-010-04 partly addressed; G-019-01 to G-019-06 confirmed or adjusted), `.specify/memory/roadmap.md` (019 done) and `AGENTS.md` (Current Status, Module Map, Known Gaps)
- [X] T047 Run `mvn spotless:apply`, then `mvn -Pcontainer-tests verify` and `mvn -P release -Dgpg.skip -DskipTests install`; record the test count in `AGENTS.md`'s Current Status; walk quickstart.md §1–§7 and confirm each expected outcome

---

## Dependencies & Execution Order

- **Setup (T001–T002)** → **Foundational (T003–T028)** → stories.
- Inside Foundational: T003 → T004, T005 → T006; T003 → T007–T010 → T011; T011 → T012 → T013; T011 → T014 → T015 →
  T016 → T017/T018 → T019 → T020/T021; T016 → T022 → T023 → T024; T011 → T025 → T026, T027 → T028.
- **US1 (T029–T033)** needs Foundational; T033 needs Postgres (T027).
- **US2 (T034–T036)** needs Foundational; T035 and T036 need the starter wiring of T031.
- **US3 (T037–T038)** needs T014–T023 only; independent of the starter.
- **US4 (T039–T041)** needs T007, T016–T019; independent of Postgres and the starter (T041's Spring case needs T031).
- **Polish (T042–T047)** after the stories; T046 and T047 last.

### Parallel opportunities

- T004 beside T005–T006; T008, T009, T010 together; T017 and T018 together; T020 and T021 together; T024 beside
  Postgres T025–T028 (different modules).
- After Foundational: US1, US3 and US4 can be built in parallel (starter, runtime tests, fixture are different files);
  US2's T034 can start with them.
- Docs T042–T045 in parallel.

## Parallel Example: Foundational records

```text
T008 NewMessage + StoredMessage        (core…/spi/store/)
T009 OutgoingMessages + OutboxCommand  (core…/spi/)
T010 SagaInstance + SagaLifecycleEntry (core…/spi/)
```

## Parallel Example: after Foundational

```text
US1: T029 → T030 → T031 → T033
US3: T037, T038
US4: T039 → T040 → T041
```

## Implementation Strategy

### MVP (User Story 1)

Phases 1 and 2, then Phase 3. The result carries a request's correlation id through every saga step, handler and
compensation in a Spring application, across hops and restarts.

### Incremental delivery

1. MVP (US1).
2. US2: operators see the metadata.
3. US3: hand-assembled hosts and failure tolerance proven.
4. US4: fixture support and examples.
5. Docs, register and verification (T042–T047).
