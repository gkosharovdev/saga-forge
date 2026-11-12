# Research: Observability with OpenTelemetry

Decisions behind [plan.md](plan.md). Each section: Decision, Rationale, Alternatives considered.

## §1 Instrument the runtime and transport directly with the OpenTelemetry API

**Decision**: `saga-forge-runtime` and `saga-forge-transport-inmemory` depend on `io.opentelemetry:opentelemetry-api`
(which brings `opentelemetry-context`, its only dependency) and instrument themselves, as OpenTelemetry's library
instrumentation guidance describes:

- `SagaRuntime.Builder.openTelemetry(OpenTelemetry)` and `InMemoryTransport.Builder.openTelemetry(OpenTelemetry)`,
  default `OpenTelemetry.noop()`, plus `telemetry(SagaTelemetrySettings)` for the switches of FR-013/FR-014
  (`SagaTelemetrySettings` is a plain value in core's `core.telemetry`, shared by both modules);
- one internal instrumentation class per module (`SagaInstrumentation`, `TransportInstrumentation`) obtains a `Tracer` and a `Meter` under the instrumentation scope
  `io.github.gkosharovdev.sagaforge` (with the Saga Forge version), and a `TextMapPropagator` from
  `openTelemetry.getPropagators()`;
- core stays dependency-free: trace context crosses `core.spi.store` as a `Map<String, String>` carrier (§3).

**Rationale**: the user's revised Q1 decision. The OpenTelemetry API is the vendor-neutral, stable (semver, no
breaking changes in 1.x) abstraction designed for libraries to depend on; with no SDK installed every call is a no-op,
so applications that do not use telemetry pay nothing. It removes an own SPI that would re-create spans, scopes,
attributes and propagation, and a second adapter with its parity test. Spring Boot applications on Micrometer Tracing
use its OpenTelemetry bridge, which shares OpenTelemetry's `Context`, so saga spans join their request traces (SC-008).

**Alternatives considered**: a Saga Forge observation SPI with OpenTelemetry and Micrometer adapters (the first Q1
answer, option C; rejected on review as re-implementing the OpenTelemetry API); Micrometer Observation in the runtime
(rejected: OpenTelemetry is the requested standard and is not Spring-centric); the OpenTelemetry Java agent alone
(rejected: it cannot see units of work, steps or compensation, only the libraries it knows).

**Constitution**: Principle I restricts core, which is unchanged. G-GOV-01 allows focused, stack-neutral dependencies
in the module that needs them; the OpenTelemetry API is the first in the runtime and transport, so the AGENTS.md Module
Map ("JDK and core only") is reworded in this feature.

## §2 What the runtime observes: a span tree per handling

**Decision**: the runtime opens spans (`SpanKind.INTERNAL`) at these points (names in [contracts/telemetry-reference.md](contracts/telemetry-reference.md)):

| Span | Opened in | Parent | Ends with |
|---|---|---|---|
| `saga_forge.handle` | `EventRouter` per saga type and instance an event reaches (`AttemptLoop.run`, duplicate, unmatched, unexpected, duplicate start) | the current context (caller's span, or the delivery span) | the `HandlingOutcome` |
| `saga_forge.attempt` | `AttemptLoop`, per `SagaUnitOfWork` | `handle` | committed / rolled back, failure classification |
| `saga_forge.step` | `SagaUnitOfWork`, per step method run (also in-process events) | `attempt` | completed / threw |
| `saga_forge.command` | `EngineCommandGateway.intercept`, per command handling (saga, non-saga, outbox delivery) | the step, the compensation, or the current context | handled / failed |
| `saga_forge.compensation` | `CompensationUnitOfWork`, per compensation | `handle` | its `CompensationStatus` |
| `saga_forge.event.ignored` | `EventRouter` when no saga type reacts | current context | — |

A retry wait is an event `saga_forge.retry.scheduled` on the `handle` span (attempt number, wait), so the wait
is time inside `handle`, not a gap (edge case "Retries waiting in process"). Terminal transitions (`COMPLETED`,
`FAILED`, `COMPENSATED`, `REQUIRES_INTERVENTION`) and the saga start are events on `handle` and counters (§4).

**Rationale**: maps onto the runtime's existing structure (router → attempt loop → unit of work → step → gateway) and
its existing `Diagnostics` call sites, which already mark every point the spec lists; one span per natural
scope keeps the trace readable.

**Alternatives considered**: one flat span per unit of work with attempts as events (rejected: User Story 1 scenario 4
wants each attempt with its failure); a span per lifecycle write (rejected: noise).

## §3 Trace context through the message store

**Decision**: a stored message carries context as a string map (`Map<String, String>`, whatever the configured
`TextMapPropagator` writes: W3C Trace Context `traceparent` / `tracestate`, and baggage). The in-memory transport:

- injects `Context.current()` when `LogAppender` builds each `NewMessage` inside the unit of work's transaction (the
  `attempt` span is current then), and stores the carrier with the message;
- on delivery extracts it and opens a `saga_forge.delivery` span (`SpanKind.CONSUMER`) with that parent, current while
  `EventReceiver.receive` / `CommandReceiver.receive` runs, so the runtime's `handle` span continues the stored trace
  (FR-005, FR-006).

`NewMessage` and `StoredMessage` gain an additive `traceContext` component (empty map when absent; compatibility
constructors keep the 007 signatures). `PostgresMessageStore` stores it in a new nullable `trace_context JSONB` column
(`schema-upgrade-010.sql`); the in-memory store keeps it; `ModulithMessageStore` adds it to `SagaForgeEnvelope`. A
message without context is delivered unchanged and starts a new trace (edge case "Message stored before the feature").

**Rationale**: a string map is what every propagator reads and writes; storing it with the message is the only way the
context survives a restart (SC-001). The transport, not the runtime, owns message storage, so it owns the carrier.

**Alternatives considered**: adding the context to `OutgoingMessages` (rejected: the transport already runs inside the
unit of work's current context at store time); OpenTelemetry span links only (rejected: the spec wants one connected
trace; links are kept for the edge case of a unit of work consuming several stored events, §2 of the contract).

## §4 Metrics through OpenTelemetry's `Meter`

**Decision**: a `DoubleHistogram` per span kind records its duration in seconds (`saga_forge.handle.duration`, ...,
`saga_forge.delivery.duration`) with the span's low-cardinality attributes and `error.type`; `LongCounter`s for
`saga_forge.saga.started`, `saga_forge.saga.ended` (`outcome`), `saga_forge.event.handled` (`handling.outcome`),
`saga_forge.retry.scheduled`, `saga_forge.retry.exhausted`; and the transport's `ObservableDoubleGauge`s
`saga_forge.message_store.messages` (`status`) and `saga_forge.message_store.oldest_unconsumed.age` (seconds), computed
from `MessageStoreQueries` — the same source as the starter's Actuator endpoint (FR-010).

**Rationale**: OpenTelemetry's metrics API is what the requested standard offers; attributes on metrics are only the
bounded ones (saga type, step, command type, outcome, classification, status), never ids (FR-009, SC-003).

**Spring Boot**: a Spring application that exports metrics only through a Micrometer `MeterRegistry` does not see
OpenTelemetry API metrics unless its OpenTelemetry SDK has a metric exporter or a bridge. Task T001 checks what Spring
Boot 4.1 configures (an `OpenTelemetry` bean with an SDK meter provider, or not) and the starter README states the
setup; the gap is G-010-05.

**Alternatives considered**: Micrometer metrics in addition (deferred, G-010-06: a later Micrometer integration would
depend on `micrometer-observation` only, and must not run beside the OpenTelemetry instrumentation, or bridged
applications would see every span twice).

## §5 Attributes, naming and sensitive data

**Decision**: attribute and metric names use the `saga_forge.` namespace and OpenTelemetry's conventions (lower-case,
dot-separated namespaces, underscores inside a word, units in `unit`, not in the name), and the standard `error.type`
and messaging semantic conventions on `delivery` spans (`messaging.system = saga_forge.inmemory`,
`messaging.operation.type = process`). Each attribute is either *low-cardinality* (allowed on metrics) or a *detail*
(spans only): `saga_forge.saga.id`, `saga_forge.command.id` and `saga_forge.unit_of_work.id` are details. Three
optional details are off by default and switched on in `SagaTelemetrySettings`: association values, event/command
contents (`toString`, truncated to 1,000 characters), failure messages (FR-013, SC-006).

**Rationale**: OpenTelemetry's naming guidelines; FR-013's privacy defaults enforced in one place, `SagaInstrumentation`.

## §6 Logs correlated with traces

**Decision**: while a span is current (`Span.current()`), a log backend with trace correlation (OpenTelemetry's
Logback/Log4j instrumentation, Micrometer Tracing's MDC) adds trace and span ids to every record the runtime writes. In
addition, `Diagnostics` appends `traceId=` and `spanId=` keys to each line when the current span context is valid, so
correlation also works with `System.Logger`'s default backend and in tests (FR-011). With no SDK installed the span
context is invalid and the lines are byte-for-byte unchanged.

**Alternatives considered**: MDC only (rejected: `System.Logger` has no MDC, and the default JUL backend shows none).

## §7 Telemetry never changes behaviour

**Decision**: the OpenTelemetry API and SDK do not throw from instrumentation calls, and the SDK isolates exporter
failures. In addition, `SagaInstrumentation` wraps each of its own operations (building attributes, `toString` of
contents) in a guard that catches and logs once per kind (`saga.telemetry.failed`) any `RuntimeException`, so a faulty
`toString` or a misbehaving custom `OpenTelemetry` never fails a unit of work (FR-012, SC-007). Spans are started and
ended in `try`/`finally` around existing code; nothing is reordered. A property test runs the runtime's generated
scenarios with `OpenTelemetry.noop()` and with an SDK on in-memory exporters and compares outcomes, stored instances and
messages (Property 37, SC-004).

## §8 Wiring: builders, the starter, the fixture

**Decision**: `SagaRuntime.Builder.openTelemetry(...)`/`telemetry(...)` and the same on `InMemoryTransport.Builder`.
The starter's new `SagaForgeTelemetryConfiguration` takes the application's `OpenTelemetry` bean (Spring Boot's
OpenTelemetry auto-configuration or Micrometer Tracing's bridge provide one), builds `SagaTelemetrySettings` from
`saga-forge.telemetry.*` properties (tracing, metrics, record-association-values, record-contents,
record-failure-messages), and passes both to the runtime and transport beans it already builds; without an
`OpenTelemetry` bean, `noop()`. An application `SagaTelemetrySettings` bean wins. `SagaFixture.Builder.openTelemetry(...)`
lets a fixture test assert on spans (FR-017); its loop-back transport needs no propagation (delivery is synchronous).

## §9 Verification

**Decision**: tests use `opentelemetry-sdk-testing` (`OpenTelemetryExtension` / `InMemorySpanExporter`,
`InMemoryMetricReader`) in test scope. A runtime test-jar `TelemetryAssertions` helper normalizes exported spans to a
tree of (name, kind, attributes, events, status) for the canonical scenarios, shared by the runtime, transport, Postgres
and starter tests. A Postgres container test proves one trace across a restart (SC-001); a Spring example container
test with Micrometer Tracing's OpenTelemetry bridge proves SC-005 and SC-008.

## §10 Versions

**Decision**: OpenTelemetry API and SDK-testing from the `opentelemetry-bom`, at the version Spring Boot 4.1.2 manages
(the starter pins Boot 4.1.2 without importing its BOM, G-DEP-01) so applications do not get two API versions; pinned
in the root `dependencyManagement`, resolved at implementation and recorded in memory.md.

## §11 Performance

**Decision**: a JMH-free micro benchmark test (`ObservationOverheadTest`, tagged `performance`, excluded by default)
runs 10,000 canonical sagas over the in-memory runtime with `OpenTelemetry.noop()` and with an SDK on a no-op exporter,
and reports the ratio. The 5% budget (SC-004) is
checked against a reference scenario that includes the in-memory transport and the Postgres store (container); the
pure in-memory runtime, whose units of work take microseconds, is reported but not held to 5% (risk R1).

## Risks

- **R1**: the 5% overhead budget is unrealistic against the pure in-memory runtime; it is measured on the reference
  scenario with storage. Mitigation: §11, and the span cost is O(steps + commands) per unit of work.
- **R2**: saga metrics through the OpenTelemetry metrics API may not reach a Spring application that exports only
  Micrometer metrics. Mitigation: T001 checks Spring Boot 4.1's setup; the starter README states it; G-010-05.
- **R3**: Spring Modulith's `@ApplicationModuleListener`s run after the commit on their own threads; whether they
  continue the saga's trace depends on Modulith's own observability support, not on this feature (G-010-03).
