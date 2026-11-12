# Implementation Plan: Observability with OpenTelemetry

**Branch**: `010-otel-observability` | **Date**: 2026-11-12 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/010-otel-observability/spec.md`

## Summary

The runtime and the in-memory transport instrument themselves directly with the OpenTelemetry API, the established
approach for libraries; it is no-op until an application installs an SDK. The plan was revised from the first Q1
answer: there is no own SPI and no Micrometer adapter.

**Runtime.** It takes the application's `OpenTelemetry` instance from its builder and starts spans:
- one around each handling of an event by a saga instance (`handle`);
- one per attempt, step, command handling and compensation.

It also records counters and duration histograms with bounded attributes. Its diagnostic lines gain trace ids.

**Transport.** It stores the propagated trace context with every message, continues it in a consumer span on
delivery, and exposes message-store gauges. The gauges read the same figures as the operator endpoint.

**Core** gains only:
- a plain settings value, `SagaTelemetrySettings`;
- the additive `traceContext` carrier on `NewMessage` and `StoredMessage`.

**Postgres** stores the carrier (`schema-upgrade-010.sql`).

**Starter** hands the application's `OpenTelemetry` bean (from Spring Boot or Micrometer Tracing's bridge) and
`saga-forge.telemetry.*` settings to the runtime and transport.

Details: [research.md](research.md).

## Technical Context

**Language/Version**: Java 21

**Primary Dependencies**:
- `saga-forge-runtime` and `saga-forge-transport-inmemory` gain `io.opentelemetry:opentelemetry-api`, which brings
  `opentelemetry-context`, at the version Spring Boot 4.1.2 manages;
- core is unchanged (JDK only).

**Storage**: the Postgres message store gains `trace_context JSONB NULL`; no saga-state change.

**Testing**: JUnit 5, AssertJ and jqwik, with these test-scope helpers:
- `opentelemetry-sdk-testing` (`InMemorySpanExporter`, `InMemoryMetricReader`);
- a `TelemetryAssertions` helper in the runtime test-jar;
- Testcontainers for the Postgres and Spring scenarios.

**Target Platform**: any JVM; Spring Boot 4.1 for the starter.

**Project Type**: multi-module Maven library; no new module.

**Performance Goals**:
- with `OpenTelemetry.noop()`: no measurable change;
- with an SDK on the reference scenario (in-memory transport over Postgres): at most 5% longer units of work (SC-004).

**Constraints**:
- no telemetry type in core (FR-002);
- telemetry never changes behaviour (FR-012);
- bounded metric attributes (FR-009);
- private details off by default (FR-013).

**Scale/Scope**:
- an internal `SagaInstrumentation` in the runtime (about 6 classes touched) and a `TransportInstrumentation` in the
  transport (about 3 classes);
- one settings class and two record components in core;
- a Postgres column, starter wiring, docs.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Check | Result |
|---|---|---|
| I. Zero-Dependency Core | Core gains a plain settings value and a string-map carrier, and no dependency. The OpenTelemetry API, a stack-neutral open standard (not Spring, a datastore or a transport), is added to the runtime and the in-memory transport, which G-GOV-01's decision allows. This feature rewords the AGENTS.md Module Map ("runtime: JDK and core only", "transport: core-only") and records it in G-GOV-01. | Pass |
| II. Annotation-Driven, Declarative Style | No annotation change; instrumentation is invisible to saga code. | Pass |
| III. Formal Spec Conformance | No change to annotation semantics or validation; new Property 37 (telemetry) in `.kiro/specs/saga-forge-framework/design.md`. | Pass (task) |
| IV. Explicit, Narrow Scope | No storage or transport added; no vendor exporter shipped. | Pass |
| V. Documentation Hierarchy | README (observability section and telemetry reference), `docs/architecture.md`, AGENTS.md, the starter README. | Pass (task) |

**Post-design re-check**: unchanged after the revision to direct instrumentation. Gaps G-010-01 to G-010-06 are
recorded in `.specify/memory/memory.md`.

## Project Structure

### Documentation (this feature)

```text
specs/010-otel-observability/
├── plan.md
├── research.md            # §1–§11, risks R1–R3
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── instrumentation.md       # builders, settings, obligations, Property 37
│   ├── telemetry-reference.md   # spans, attributes, metrics, starter properties
│   └── message-context.md       # trace context in NewMessage/StoredMessage, Postgres, Modulith
└── tasks.md
```

### Source Code (repository root)

```text
saga-forge-core/.../core/telemetry/SagaTelemetrySettings.java                  # new, plain value
saga-forge-core/.../core/spi/store/NewMessage.java, StoredMessage.java         # + traceContext

saga-forge-runtime/pom.xml                                                     # + opentelemetry-api
saga-forge-runtime/.../runtime/SagaRuntime.java                                # Builder.openTelemetry, telemetry
saga-forge-runtime/.../runtime/internal/
    SagaInstrumentation (new: tracer, meter, attribute keys, guard), EngineSettings, EventRouter, AttemptLoop,
    SagaUnitOfWork, EngineCommandGateway, CompensationRun/CompensationUnitOfWork, Diagnostics
saga-forge-runtime/src/test/.../telemetry/
    TelemetryAssertions (test-jar), SpanTreeTest, SagaMetricsTest, TelemetryParityPropertyTest,
    FaultyInstrumentationTest, DiagnosticsCorrelationTest, TelemetryOverheadTest

saga-forge-transport-inmemory/pom.xml                                          # + opentelemetry-api
saga-forge-transport-inmemory/.../InMemoryTransport.java (Builder.openTelemetry, telemetry),
    internal/TransportInstrumentation (new), internal/LogAppender, internal/Relay, internal/MessageStoreGauges (new)
saga-forge-storage-postgres/.../PostgresMessageStore.java; schema.sql; schema-upgrade-010.sql
saga-forge-spring-boot-starter/.../telemetry/SagaForgeTelemetryConfiguration (+ properties); modulith/SagaForgeEnvelope
saga-forge-test/.../SagaFixture.java                                           # Builder.openTelemetry
saga-forge-example-spring/src/test/.../TelemetryContainerTest.java
```

**Structure Decision**:
- instrumentation lives where the work happens, in the runtime and the transport;
- core holds only plain values;
- no new module.

## Phases (for /speckit-tasks)

1. **Foundation**:
   - the API dependency, settings and builder methods;
   - `SagaInstrumentation` with its guard;
   - `TelemetryAssertions`;
   - Property 37 and the no-change parity property test.
2. **Tracing** (US1):
   - runtime spans;
   - the message carrier in core, the stores, Postgres and Modulith;
   - transport inject and extract, and the consumer span;
   - a container test across a restart.
3. **Metrics** (US2): histograms, counters, message-store gauges, cardinality.
4. **Logs** (US3): diagnostics trace ids.
5. **Starter** (US4):
   - auto-configuration and properties;
   - check Spring Boot 4.1's OpenTelemetry setup and its metrics export;
   - the Spring example container test with the Micrometer Tracing bridge.
6. **Plain Java and fixture** (US5).
7. **Polish**: overhead, docs, AGENTS.md (Module Map rewording), memory, full build.

## Complexity Tracking

| Item | Why Needed | Simpler Alternative Rejected Because |
|---|---|---|
| Additive `traceContext` on `NewMessage`/`StoredMessage` and a Postgres column | Trace continuity across the message store and restarts (FR-005, SC-001) | Without storing the context, a delivery after a restart cannot continue the trace |
| A third-party dependency in the runtime and transport | Direct instrumentation (revised Q1) | An own SPI with adapters re-implements the OpenTelemetry API |
