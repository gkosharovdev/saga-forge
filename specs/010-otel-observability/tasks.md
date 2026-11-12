# Tasks: Observability with OpenTelemetry

**Input**: Design documents from `/specs/010-otel-observability/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md),
[contracts/](contracts/), [quickstart.md](quickstart.md)

**Tests**: requested. The spec requires tests for:
- telemetry changing nothing (FR-012, SC-004);
- trace continuity across the message store (SC-001);
- the privacy defaults (SC-006);
- bounded cardinality (SC-003);
- the Spring bridge (SC-005, SC-008).

Each story has test tasks written before its implementation.

**Organization**: tasks are grouped by user story:
- US1, tracing (P1);
- US2, metrics (P1);
- US3, log correlation (P2);
- US4, Spring Boot (P2);
- US5, plain Java and the test fixture (P3).

The design is direct instrumentation with `opentelemetry-api` (research §1, revised Q1): there is no own SPI and no
Micrometer adapter.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an unfinished task)
- **[Story]**: the user story the task belongs to (US1–US5)

## Path Conventions

Paths are written in full from the repository root. The packages are:
- core: `io.github.gkosharovdev.sagaforge.core`;
- runtime: `io.github.gkosharovdev.sagaforge.runtime`;
- transport: `io.github.gkosharovdev.sagaforge.transport.inmemory`;
- starter: `io.github.gkosharovdev.sagaforge.spring`.

---

## Phase 1: Setup

- [X] T001 Pin the OpenTelemetry versions in the root `pom.xml`:
  - in `dependencyManagement`, import `io.opentelemetry:opentelemetry-bom` at the version Spring Boot 4.1.2 manages (look it up in Boot 4.1.2's `spring-boot-dependencies`), and add `io.opentelemetry:opentelemetry-sdk-testing` in test scope;
  - add `opentelemetry-api` as a main dependency of `saga-forge-runtime/pom.xml` and `saga-forge-transport-inmemory/pom.xml`, and `opentelemetry-sdk-testing` in test scope to both.

  Then check what Spring Boot 4.1.2 auto-configures:
  - which `OpenTelemetry` bean it provides (its OpenTelemetry starter, and Micrometer Tracing's `micrometer-tracing-bridge-otel`);
  - whether that SDK has a metric exporter or meter provider.

  Record the versions and findings in `.specify/memory/memory.md` under "Observability (010)" (G-010-05).

---

## Phase 2: Foundational (blocking prerequisites)

**Purpose**: the settings, builder methods, instrumentation classes with their guard, test helpers, and proof that nothing changes ([contracts/instrumentation.md](contracts/instrumentation.md)). Blocks every story.

### Tests

- [X] T002 [P] Write `saga-forge-core/src/test/java/io/github/gkosharovdev/sagaforge/core/telemetry/SagaTelemetrySettingsTest.java`:
  - the defaults (tracing and metrics on, details off);
  - immutability through `with...` methods;
  - `equals`.
- [X] T003 [P] Write `saga-forge-runtime/src/test/java/io/github/gkosharovdev/sagaforge/runtime/telemetry/TelemetryParityPropertyTest.java` for Property 37. Model it on 009's `CommandInterceptorPropertyTest` over generated `fixtures/interception/TripModel` event sequences. An engine with `OpenTelemetry.noop()`, one with an SDK over `InMemorySpanExporter`/`InMemoryMetricReader`, and one built without `openTelemetry(...)` must give equal outcomes, stored instances, handled-command identities and outbox batches. Every exported span must be ended and properly nested.
- [X] T004 [P] Write `saga-forge-runtime/src/test/java/io/github/gkosharovdev/sagaforge/runtime/telemetry/FaultyInstrumentationTest.java` (FR-012, SC-007). Use:
  - `recordContents` on with a command whose `toString` throws;
  - an `OpenTelemetry` test double whose `Tracer` throws from `spanBuilder`.

  Neither may change any `HandlingReport` or stored instance, and each is logged once as `saga.telemetry.failed`.

### Implementation

- [X] T005 [P] Create `saga-forge-core/src/main/java/io/github/gkosharovdev/sagaforge/core/telemetry/SagaTelemetrySettings.java`:
  - an immutable value with `defaults()`, `withTracing`, `withMetrics`, `withRecordAssociationValues`, `withRecordContents` and `withRecordFailureMessages`, plus accessors;
  - no telemetry type;
  - `package-info.java`.
- [X] T006 Add `openTelemetry` (default `OpenTelemetry.noop()`) and `telemetry` (default `SagaTelemetrySettings.defaults()`) to `saga-forge-runtime/src/main/java/io/github/gkosharovdev/sagaforge/runtime/internal/EngineSettings.java`, keeping the existing constructors. Add `SagaRuntime.Builder.openTelemetry(OpenTelemetry)` and `telemetry(SagaTelemetrySettings)` to `SagaRuntime.java`, with Javadoc.
- [X] T007 Create `saga-forge-runtime/src/main/java/io/github/gkosharovdev/sagaforge/runtime/internal/SagaInstrumentation.java`:
  - it holds the `Tracer` and `Meter` under instrumentation scope `io.github.gkosharovdev.sagaforge` with the Saga Forge version, read from a generated `version.properties` resource or the package's implementation version, using `TracerProvider.noop()`/`MeterProvider.noop()` when switched off;
  - the `AttributeKey` constants of [contracts/telemetry-reference.md](contracts/telemetry-reference.md), with a set marking the low-cardinality ones;
  - helpers `startSpan(name, parent?, attributes)`, `fail(span, throwable)`, `content(object)` (guarded `toString`, truncated to 1,000 characters) and the detail filter;
  - a guard that catches a `RuntimeException` in its own code and logs it once per kind through `Diagnostics.telemetryFailed`.

  `EngineRuntime` builds one from the settings.
- [X] T008 [P] Create `saga-forge-runtime/src/test/java/io/github/gkosharovdev/sagaforge/runtime/support/TelemetryAssertions.java` (test-jar):
  - builds an `OpenTelemetrySdk` with `InMemorySpanExporter`, `InMemoryMetricReader` and the W3C propagator;
  - normalizes the exported spans to a tree of (name, kind, low-cardinality attributes, details, events, status);
  - normalizes the metrics to (name, instrument, attributes) series;
  - offers assertions used by the runtime, transport, Postgres, fixture and starter tests.
- [X] T009 Add `.openTelemetry(OpenTelemetry)` and `.telemetry(SagaTelemetrySettings)` to `InMemoryTransport.Builder` in `saga-forge-transport-inmemory/src/main/java/io/github/gkosharovdev/sagaforge/transport/inmemory/InMemoryTransport.java`. Create `internal/TransportInstrumentation.java`, a small guarded tracer and meter holder under the same instrumentation scope with the messaging attribute keys, and pass it to `internal/Relay` and `internal/LogAppender`.
- [X] T010 Add Property 37 (telemetry) to `.kiro/specs/saga-forge-framework/design.md` after Property 36, worded as in [contracts/instrumentation.md](contracts/instrumentation.md), and extend the closing note.
- [X] T011 Run `mvn -B -pl saga-forge-core,saga-forge-runtime,saga-forge-transport-inmemory -am verify`. T002 to T004 pass, and every existing test passes unchanged.

**Checkpoint**: both modules take an `OpenTelemetry`, and nothing has changed.

---

## Phase 3: User Story 1: follow one business request through a saga (Priority: P1) 🎯 MVP

**Goal**: the span tree of research §2, and trace context carried through the message store.

**Independent Test**: one trace from a root span through `handle` → `attempt` → `step` → `command` for every step and command of order `o-1`, compensation included, also across the message store and a restart.

### Tests for User Story 1

- [X] T012 [P] [US1] Write `saga-forge-runtime/src/test/java/io/github/gkosharovdev/sagaforge/runtime/telemetry/SpanTreeTest.java` with `TelemetryAssertions`, over the `TripModel` and compensation fixtures:
  - acceptance scenarios 1 to 6: spans parented by the caller's current span; a `command` span per handling with type, identity, result and delivery; one `attempt` span per attempt with a `saga_forge.retry.scheduled` event; `compensation` spans with the trigger; `ERROR` status with `error.type` and classification;
  - the edge cases: duplicate, unmatched and unexpected events give a short `handle`; an ignored event gives `event.ignored`; in-process events stay inside the attempt; a non-saga command gives a root `command`;
  - SC-006: by default no association value, content or failure message is set, and each switch adds its detail.
- [X] T013 [P] [US1] Extend `MessageStoreContract` in `saga-forge-transport-inmemory/src/test/java/io/github/gkosharovdev/sagaforge/transport/inmemory/` with a case: `traceContext` round-trips through `append`, `lease` and queries, and empty stays empty. The in-memory, Postgres and Modulith stores run it through their contract subclasses.
- [X] T014 [P] [US1] Write `saga-forge-transport-inmemory/src/test/java/io/github/gkosharovdev/sagaforge/transport/inmemory/TraceContextDeliveryTest.java` with `TelemetryAssertions`:
  - a message appended under a span carries a W3C `traceparent`;
  - its delivery opens a `CONSUMER` `saga_forge.delivery` span with that parent, and the runtime's `handle` is its child;
  - a message without context starts a new trace;
  - a failed delivery sets `ERROR` on the delivery span;
  - several queued events: the first is the parent and the others are links.

### Implementation for User Story 1

- [X] T015 [US1] Instrument `saga-forge-runtime/src/main/java/io/github/gkosharovdev/sagaforge/runtime/internal/EventRouter.java`:
  - a `saga_forge.handle` span per reaction (start, resume per active instance, duplicate start, duplicate, unmatched, unexpected), current while its attempt loop runs, with the saga type, event type and saga id (a detail);
  - `saga_forge.event.ignored` when no saga type reacts;
  - `saga_forge.handling.outcome` set before the span ends.
- [X] T016 [US1] Instrument `saga-forge-runtime/src/main/java/io/github/gkosharovdev/sagaforge/runtime/internal/AttemptLoop.java`:
  - a `saga_forge.attempt` span per `SagaUnitOfWork` run, with the attempt number, unit-of-work id, result and classification;
  - a `saga_forge.retry.scheduled` event on the current `handle` span before each pause;
  - `saga_forge.saga.started` and `saga_forge.saga.ended` events on terminal transitions.
- [X] T017 [US1] Instrument `saga-forge-runtime/src/main/java/io/github/gkosharovdev/sagaforge/runtime/internal/SagaUnitOfWork.java`: a `saga_forge.step` span around each step method invocation, in-process events included, failed when the step throws.
- [X] T018 [US1] Instrument `saga-forge-runtime/src/main/java/io/github/gkosharovdev/sagaforge/runtime/internal/EngineCommandGateway.java`:
  - a `saga_forge.command` span in `intercept(...)` around the interceptor call, with the command type, identity, delivery (`outbox` when called from `receive(OutboxCommand)`), result and the content detail;
  - a skipped command gets a span with result `skipped`.
- [X] T019 [US1] Instrument `saga-forge-runtime/src/main/java/io/github/gkosharovdev/sagaforge/runtime/internal/CompensationRun.java` and `CompensationUnitOfWork.java`:
  - a `saga_forge.compensation` span per compensation, with the compensated step, status and trigger;
  - a `saga_forge.compensation.started` event on `handle`.
- [X] T020 [US1] Add a `traceContext` component (`Map<String, String>`, never null, unmodifiable copy) to `saga-forge-core/src/main/java/io/github/gkosharovdev/sagaforge/core/spi/store/NewMessage.java` and `StoredMessage.java`, keeping the 007 constructors (empty map). Update every construction site across the modules; check them with `grep -rn "new NewMessage\|new StoredMessage"`.
- [X] T021 [US1] Store the context in each message store:
  - the in-memory test store, `saga-forge-transport-inmemory/src/test/.../InMemoryMessageStore.java`;
  - `saga-forge-storage-postgres/src/main/java/io/github/gkosharovdev/sagaforge/storage/postgres/PostgresMessageStore.java`, as a `trace_context JSONB NULL` column through `JsonCodec`, `NULL` read as empty;
  - the column in `saga-forge-storage-postgres/src/main/resources/schema.sql`, plus a new `schema-upgrade-010.sql` with `ALTER TABLE saga_message_store ADD COLUMN IF NOT EXISTS trace_context JSONB`;
  - `saga-forge-spring-boot-starter/src/main/java/io/github/gkosharovdev/sagaforge/spring/modulith/SagaForgeEnvelope.java` and `ModulithMessageStore.java`, where old envelopes read as an empty map.

  Extend `SchemaTest` for the column.
- [X] T022 [US1] Make the transport carry the context:
  - in `saga-forge-transport-inmemory/src/main/java/io/github/gkosharovdev/sagaforge/transport/inmemory/internal/LogAppender.java`, inject `Context.current()` with the propagator into each `NewMessage.traceContext`;
  - in `internal/Relay.java`, extract it and wrap each delivery in a `CONSUMER` `saga_forge.delivery` span with the messaging attributes, made current around `EventReceiver.receive` or `CommandReceiver.receive`, with `ERROR` on failure and links for further events of one unit of work.
- [X] T023 [US1] Run `mvn -B -pl saga-forge-runtime,saga-forge-transport-inmemory,saga-forge-storage-postgres -am verify`. T012 to T014 must pass.
- [X] T024 [US1] Write and run `saga-forge-storage-postgres/src/test/java/io/github/gkosharovdev/sagaforge/storage/postgres/TraceContinuityContainerTest.java` (`@Tag("container")`, SC-001):
  - the in-memory transport over `PostgresMessageStore`, a saga split across units of work, and `TelemetryAssertions`' SDK;
  - a root span publishes the start event;
  - the transport stops and restarts between deliveries;
  - every saga span must share the root's trace id.

  Run with `-Pcontainer-tests`.

**Checkpoint**: US1 works. This is the MVP.

---

## Phase 4: User Story 2: dashboards and alerts from metrics (Priority: P1)

**Goal**: the histograms, counters and gauges of [contracts/telemetry-reference.md](contracts/telemetry-reference.md), with bounded attributes.

**Independent Test**: 10 orders of which 2 are out of stock give 10 starts, 8 `completed:completeOrder`, 2 `compensated`, a duration distribution per step, and a backlog of 0 once delivered.

### Tests for User Story 2

- [X] T025 [P] [US2] Write `saga-forge-runtime/src/test/java/io/github/gkosharovdev/sagaforge/runtime/telemetry/SagaMetricsTest.java` with `TelemetryAssertions`. It covers:
  - acceptance scenarios 1, 2 and 4;
  - durations in seconds;
  - with metrics off, nothing exported; with tracing off, metrics still exported;
  - SC-003: 10 and 1,000 orders give the same set of series, and no detail attribute appears on any metric.
- [X] T026 [P] [US2] Write `saga-forge-transport-inmemory/src/test/java/io/github/gkosharovdev/sagaforge/transport/inmemory/MessageStoreGaugesTest.java`:
  - the gauges report `MessageStoreQueries.status()` counts per status and the oldest unconsumed age (FR-010);
  - reads are cached for the polling interval;
  - the gauges close at `stop()`.

### Implementation for User Story 2

- [X] T027 [US2] Record metrics in `SagaInstrumentation`:
  - a `DoubleHistogram` per span name (`<name>.duration`, unit `s`, low-cardinality attributes plus `error.type`), recorded when the span ends;
  - `LongCounter`s for `saga_forge.saga.started` and `saga_forge.saga.ended` in `EventRouter`, `AttemptLoop`, `CompensationRun` and the `FAILED` write;
  - `saga_forge.event.handled` per result, in `EventRouter`;
  - `saga_forge.retry.scheduled` and `saga_forge.retry.exhausted`, in `AttemptLoop`.

  Use low-cardinality keys only.
- [X] T028 [US2] Create `saga-forge-transport-inmemory/src/main/java/io/github/gkosharovdev/sagaforge/transport/inmemory/internal/MessageStoreGauges.java`. It registers `ObservableDoubleGauge`s `saga_forge.message_store.messages` (per `MessageStatus`) and `saga_forge.message_store.oldest_unconsumed.age` at transport start, reads one cached `MessageStoreQueries.status()` per polling interval, and closes them at stop. Record the delivery-duration histogram in `Relay`.
- [X] T029 [US2] Run `mvn -B -pl saga-forge-runtime,saga-forge-transport-inmemory -am verify`. T025 and T026 must pass.

---

## Phase 5: User Story 3: jump from a trace to the logs (Priority: P2)

- [X] T030 [P] [US3] Write `saga-forge-runtime/src/test/java/io/github/gkosharovdev/sagaforge/runtime/telemetry/DiagnosticsCorrelationTest.java` with `RecordingLogger`:
  - with an SDK, lines inside a unit of work carry the current span's `traceId=` and `spanId=`;
  - with `OpenTelemetry.noop()`, every line is byte-for-byte as in a run built without `openTelemetry(...)`.
- [X] T031 [US3] In `saga-forge-runtime/src/main/java/io/github/gkosharovdev/sagaforge/runtime/internal/Diagnostics.java`, append `traceId` and `spanId` to `Line` when `Span.current().getSpanContext().isValid()`.
- [X] T032 [US3] Run `mvn -B -pl saga-forge-runtime verify`. T030 must pass.

---

## Phase 6: User Story 4: Spring Boot with no code (Priority: P2)

**Goal**: the starter passes the application's `OpenTelemetry` bean and the `saga-forge.telemetry.*` settings to the runtime and the transport.

**Independent Test**: the Spring example with Micrometer Tracing's OpenTelemetry bridge and an in-memory exporter, one web request: the saga spans are children of the request span, and saga metrics are exported, with no Saga Forge code.

- [X] T033 [P] [US4] Write `saga-forge-spring-boot-starter/src/test/java/io/github/gkosharovdev/sagaforge/spring/telemetry/TelemetryAutoConfigurationTest.java` with `ApplicationContextRunner`:
  - an `OpenTelemetry` bean reaches the runtime and the transport;
  - without one, both get `OpenTelemetry.noop()`;
  - `saga-forge.telemetry.enabled=false` gives `noop()`;
  - the properties reach `SagaTelemetrySettings`;
  - an application `SagaTelemetrySettings` bean wins.
- [X] T034 [US4] Create `saga-forge-spring-boot-starter/src/main/java/io/github/gkosharovdev/sagaforge/spring/telemetry/SagaForgeTelemetryConfiguration.java`:
  - add `saga-forge.telemetry.*` to `SagaForgeProperties`;
  - register it in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`, ordered after Spring Boot's OpenTelemetry and Micrometer Tracing auto-configurations;
  - pass the instance and the settings into `spring/runtime/SagaRuntimeConfiguration` and `spring/transport/InMemoryTransportConfiguration`.
- [X] T035 [US4] Document the six `saga-forge.telemetry.*` properties in `saga-forge-spring-boot-starter/README.md`:
  - add them to the properties table, so `ReadmePropertiesTest` stays green;
  - add a "With OpenTelemetry" setup section covering both Spring Boot's OpenTelemetry setup and Micrometer Tracing's bridge;
  - state T001's finding on metrics export.
- [X] T036 [US4] Write `saga-forge-example-spring/src/test/java/io/github/gkosharovdev/sagaforge/example/spring/TelemetryContainerTest.java` (`@Tag("container")`, SC-005, SC-008):
  - add Actuator, `micrometer-tracing-bridge-otel` and `opentelemetry-sdk-testing` in test scope to `saga-forge-example-spring/pom.xml`;
  - use an in-memory span exporter bean;
  - post an order through a web endpoint (add a minimal test controller if the example has none);
  - the saga spans must be children of the request span, `saga_forge.saga.ended` must be 1 with outcome `completed:completeOrder`, and with `saga-forge.telemetry.tracing.enabled=false` no saga span may be exported.
- [X] T037 [US4] Run `mvn -B -pl saga-forge-spring-boot-starter -am verify`, then the example's container test with `-Pcontainer-tests`.

---

## Phase 7: User Story 5: plain Java and the test fixture (Priority: P3)

- [X] T038 [P] [US5] Write `saga-forge-test/src/test/java/io/github/gkosharovdev/sagaforge/test/TelemetryTest.java`:
  - a fixture without `openTelemetry(...)` behaves as before;
  - a fixture with `TelemetryAssertions`' SDK reports the canonical span tree and counters for a given/when scenario;
  - `FixtureParityTest` still passes.
- [X] T039 [US5] Add `openTelemetry(OpenTelemetry)` and `telemetry(SagaTelemetrySettings)` to `SagaFixture.Builder` in `saga-forge-test/src/main/java/io/github/gkosharovdev/sagaforge/test/SagaFixture.java`, passed to the runtime. `FixtureLogger` must keep its sagaId parsing with the trace keys present.
- [X] T040 [P] [US5] Write `saga-forge-transport-inmemory/src/test/java/io/github/gkosharovdev/sagaforge/transport/inmemory/PlainJavaTelemetryTest.java` (FR-015). Assemble the runtime and transport by hand, exactly as the README snippet will show (T043), with one `OpenTelemetry` shared by both, and assert the span tree and metrics of `SpanTreeTest` and `SagaMetricsTest` for the canonical scenario.
- [X] T041 [US5] Run `mvn -B -pl saga-forge-test,saga-forge-transport-inmemory -am verify`.

---

## Phase 8: Polish and cross-cutting concerns

- [X] T042 Write `saga-forge-runtime/src/test/java/io/github/gkosharovdev/sagaforge/runtime/telemetry/TelemetryOverheadTest.java` (`@Tag("performance")`). Add `performance` to the default excluded groups in the root `pom.xml` alongside `container`. Over 10,000 canonical sagas, compare `OpenTelemetry.noop()` with an SDK on a no-op exporter and report the ratio. Record the ratio and the Postgres reference measurement in memory.md G-010-02 (SC-004).
- [X] T043 [P] Add an "Observability" section to the root `README.md`:
  - the pitch;
  - Spring Boot setup with no code;
  - the plain-Java snippet tested by T040;
  - the privacy defaults;
  - the Postgres upgrade;
  - the full span, attribute and metric reference from [contracts/telemetry-reference.md](contracts/telemetry-reference.md);
  - example PromQL queries for each User Story 2 alert: failing sagas, compensations, exhausted retries, set-aside messages, oldest unconsumed age (FR-019).

  Update the Status paragraph.
- [X] T044 [P] Update `docs/architecture.md`:
  - the status table;
  - the runtime and transport responsibilities (OpenTelemetry instrumentation);
  - a short "Observability" section on the span tree and the context through the message store.
- [X] T045 [P] Update `AGENTS.md`:
  - "Current Status": a 010 bullet;
  - "Module Map": the runtime is "JDK, core and `opentelemetry-api`", and the transport likewise;
  - "Known Gaps": G-010-01 to G-010-06.

  Record the wording change in G-GOV-01 in memory.md.
- [X] T046 Update `.specify/memory/memory.md`:
  - the versions and Spring findings from T001 (G-010-05);
  - the overhead from T042;
  - G-008-02's metrics remark, marked as covered by 010;
  - any gap found during implementation, as G-010-NN.
- [X] T047 Run [quickstart.md](quickstart.md) scenarios 1 to 8 and fix drift between docs and code.
- [X] T048 Run `mvn -B clean verify`, then `mvn -B clean verify -Pcontainer-tests`. Record the date and test count in AGENTS.md "Current Status".

---

## Dependencies and Execution Order

- **Setup (Phase 1)** comes first.
- **Foundational (Phase 2)** blocks every story.
- **US1 (Phase 3)** is the MVP. T020 and T021 (the message records and stores) come before T022 (the transport).
- **US2 (Phase 4)** needs Phase 2. Its runtime metrics edit files that US1 touches, so run it after US1.
- **US3 (Phase 5)** needs Phase 2 only.
- **US4 (Phase 6)** needs US1 and US2.
- **US5 (Phase 7)** needs US1 and US2 for the assertions.
- **Polish (Phase 8)** comes last, and T048 is the final task.

### Parallel opportunities

- Phase 2: T002, T003, T004, T005 and T008 together; then T006 → T007; T009 and T010 alongside them.
- US1: T012 to T014 together. T015 to T019 are separate runtime files and can run in parallel after T007. T020 → T021 → T022 run in sequence.
- US2: T025 and T026 together; T028 alongside T027.
- Polish: T043 to T045 together.

## Implementation Strategy

1. MVP: Phases 1 to 3. Validate with T024, the one-trace-across-a-restart test.
2. US2 metrics, then US3 logs, US4 Spring and US5 plain Java and the fixture.
3. Polish: overhead, docs and the full build with containers.

## Implementation notes (2026-11-12)

- **T001**: OpenTelemetry 1.62.0 (Spring Boot 4.1.x's version); Spring Boot defines no `SdkMeterProvider`, so saga
  metrics need one (G-010-05, starter README).
- **T007/T027**: counters and the `saga.started`/`saga.ended` events are emitted once, from the `handle` span's result
  in `EventRouter`, rather than from `AttemptLoop`, `CompensationRun` and the `FAILED` write separately.
- **T014/T022**: no span links: the transport delivers each stored message on its own, so every delivery continues one
  stored context. A duplicate of an active instance shows one `attempt` span with result `not_run` (found by the unit
  of work's locked read).
- **T021**: Spring Modulith interop needed no envelope change: its delivery records go through
  `PostgresMessageStore`, which stores `trace_context`.
- **T036**: the "request" is a Micrometer Tracing span opened in the test (through the OpenTelemetry bridge), not an
  HTTP endpoint; it proves the same parentage without adding a web stack to the example.
- **T040**: plain-Java coverage lives in `TransportTelemetryTest` (runtime and transport assembled by hand around one
  `OpenTelemetry`).
- **T042**: `TelemetryOverheadTest` runs only with `-Dsaga.performance=true` instead of a `performance` tag; measured
  ratio 0.996 in memory (G-010-02).

## Notes

- Record any gap found during implementation in `.specify/memory/memory.md` as G-010-NN.
- Commit after each phase checkpoint.
