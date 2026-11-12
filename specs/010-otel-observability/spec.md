# Feature Specification: Observability with OpenTelemetry

**Feature Branch**: `010-otel-observability`

**Created**: 2026-11-12

**Status**: Draft

**Input**: User description: "010 observability with OpenTelemetry"

## Context

A Saga Forge application runs its sagas in production: the runtime routes events to saga instances, runs each
saga-scoped unit of work, retries transient failures, compensates, and the in-memory transport carries events and
outbox commands between units of work through a durable message store. What an operator cannot do today is **see** any
of that from the tools they already use:

- No trace shows one business request crossing from the code that published the start event, through each saga step,
  the commands those steps sent and their handlers, into the steps of other units of work that the message store
  delivered later. Each unit of work is invisible to distributed tracing.
- No metric says how many sagas start, complete, fail or compensate, how long steps take, how often steps retry, or how
  far the message store has fallen behind. The Spring starter's read-only operator endpoint reports message counts on
  request, but nothing feeds a dashboard or an alert (G-008-02).
- The runtime's diagnostics are structured log lines, but they carry no trace identity, so an operator cannot jump from
  a slow trace to the log lines of the same unit of work.

OpenTelemetry is the vendor-neutral standard operators use for traces, metrics and their correlation with logs, and
every common backend accepts it. This feature makes a Saga Forge application emit OpenTelemetry-compatible traces and
metrics about its sagas, with trace context that survives the trip through the message store, while keeping the core
free of any observability dependency (Constitution Principle I).

Running example: the canonical `OrderProcessingSaga`. An HTTP request places order `o-1` and publishes
`OrderCreatedEvent`; `handleOrderCreated` sends `ProcessPaymentCommand`, handled by `PaymentService`; `processPayment`
sends `ReserveStockCommand`, handled by `Order`; `completeOrder` ends the saga, or `rejectOrder` triggers
`compensatePayment`'s refund.

## Clarifications

### Session 2026-11-12

- Q: Which telemetry API does the support build on? → A: A Saga Forge-owned, dependency-free observation SPI that the
  runtime and transport call, with two adapters: the OpenTelemetry API and Micrometer Observation (option C).
  **Revised the same day** (user decision, before planning was implemented): the runtime and the in-memory transport
  instrument themselves directly with the OpenTelemetry API, the established library instrumentation approach; it is
  no-op until the application installs an OpenTelemetry SDK. No own SPI and no Micrometer adapter; Spring Boot
  applications on Micrometer Tracing join the same traces through its OpenTelemetry bridge. Core stays free of any
  telemetry dependency.
- Q: How far does the operator surface grow? → A: Telemetry only; the starter's endpoint and health indicator stay as
  they are, sharing their message-store figures with the metrics (option A). Saga-instance views and write actions
  stay deferred (G-008-02).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Follow one business request through a saga (Priority: P1)

An operator investigating a slow or failed order opens the trace of the HTTP request that placed it and sees, as one
connected trace: the publication of the start event, each saga unit of work, the steps it ran with their attempts, the
commands each step sent and the handler that handled each one, the compensations that ran, and the outcome. A unit of
work that ran later, after the message store delivered an event or an outbox command, continues the same trace instead
of starting an unrelated one.

**Why this priority**: tracing is what lets an operator answer "what happened to this order and where did the time go"
across asynchronous hops; without it, every unit of work is an isolated island.

**Independent Test**: with tracing on, placing order `o-1` produces one trace that contains the request, a span per
unit of work of `OrderProcessingSaga` for `o-1`, child spans for `handleOrderCreated`, `processPayment` and
`completeOrder`, a span per command (`ProcessPaymentCommand`, `ReserveStockCommand`) with its handler, and the saga's
outcome `completeOrder`.

**Acceptance Scenarios**:

1. **Given** a request that publishes a saga's start event, **When** the saga runs, **Then** the unit of work's span is
   a child of the publishing code's active span and names the saga type, the saga id and the steps that ran.
2. **Given** a step that sends commands, **When** each command is handled, **Then** a span per command handling records
   the command type, its identity, the handler and whether it succeeded, as a child of the step's span.
3. **Given** an event or outbox command stored in the message store and delivered later (after a restart, or on another
   worker), **When** it is handled, **Then** the handling continues the trace that stored it.
4. **Given** a step that fails transiently and is retried, **When** it finally completes or exhausts its retries,
   **Then** each attempt is visible with its attempt number, the wait before it and the failure that caused the retry.
5. **Given** a saga that compensates, **When** compensation runs, **Then** each compensation's unit of work is visible
   in the same trace with the trigger (the step and why) and its own outcome.
6. **Given** a failure that ends the saga `FAILED` or `REQUIRES_INTERVENTION`, **When** the operator opens the trace,
   **Then** the failing span is marked as an error with the failure's type, message and classification.

---

### User Story 2 - Watch saga health on a dashboard and alert on it (Priority: P1)

An operator builds a dashboard and alerts from metrics the application emits: sagas started, completed, failed,
compensated and requiring intervention per saga type and outcome; step and unit-of-work durations; retries and
exhausted retries; compensations run and failed; commands handled and failed per command type; and the message store's
backlog (unconsumed and set-aside messages, age of the oldest unconsumed message), so an alert fires when messages pile
up or a saga type starts failing.

**Why this priority**: metrics are how a team learns about trouble before a customer does; the message store backlog in
particular is today visible only on request (G-008-02).

**Independent Test**: with metrics on, running ten orders of which two are out of stock reports, for
`OrderProcessingSaga`, 10 starts, 8 completions with outcome `completeOrder`, 2 compensations triggered by
`rejectOrder`, step duration distributions for each step, and a message-store backlog of zero once delivery caught up.

**Acceptance Scenarios**:

1. **Given** sagas that reach each terminal status, **When** the metrics are read, **Then** each terminal outcome is
   counted per saga type, with the outcome step or failure classification as a dimension.
2. **Given** retried steps, **When** the metrics are read, **Then** attempts, retries and exhausted retries are counted
   per saga type and step.
3. **Given** messages waiting in the message store, **When** the metrics are read, **Then** the counts per message status
   and the age of the oldest unconsumed message match what the operator endpoint reports at the same moment.
4. **Given** a duplicate, unmatched or unexpected event, **When** the metrics are read, **Then** it is counted per saga
   type and handling outcome, so a spike in unmatched events is visible.
5. **Given** a metric dimension, **Then** its set of values is bounded by the model (saga types, steps, command types,
   outcomes, classifications), never by business data such as order ids, so metric cardinality stays fixed.

---

### User Story 3 - Jump from a trace to the logs of the same unit of work (Priority: P2)

An operator looking at a slow unit of work in a trace jumps to its log lines, and from a `saga.uow.rolledBack` log line
to its trace, because every diagnostic the runtime writes carries the active trace identity.

**Why this priority**: the runtime already explains itself in structured logs; correlating them with traces turns two
partial views into one.

**Independent Test**: with tracing on, every runtime diagnostic line written during the unit of work for order `o-1`
carries the trace identity of that unit of work's span.

**Acceptance Scenarios**:

1. **Given** tracing on and a log setup that records trace identity, **When** the runtime logs a diagnostic inside a
   unit of work, **Then** the log record carries that unit of work's trace and span identity.
2. **Given** tracing off, **When** the runtime logs, **Then** the diagnostics are unchanged from today.

---

### User Story 4 - Turn it on in a Spring Boot application with no code (Priority: P2)

A developer on the Spring Boot starter who already has a telemetry setup (Spring Boot's OpenTelemetry setup, or
Micrometer Tracing with its OpenTelemetry bridge) sees saga traces and metrics in the same backend as the application's
HTTP and database telemetry, with no code. Properties switch tracing or metrics off and choose which optional details
are recorded.

**Why this priority**: most adopters use the starter (008); observability that needs hand wiring would go unused.

**Independent Test**: a Spring Boot application with the starter, the storage module and an in-memory telemetry
exporter, publishing one `OrderCreatedEvent` through a web request, exports a trace in which the
saga's spans are children of the request span, and the saga metrics, without any Saga Forge code in the application.

**Acceptance Scenarios**:

1. **Given** the starter and the application's existing telemetry setup, **When** the application runs, **Then** saga
   telemetry goes to the application's configured backend with no further code.
2. **Given** a property that turns tracing or metrics off, **When** the application runs, **Then** that signal is not
   emitted and the saga behaves exactly as without the support.
3. **Given** no telemetry setup in the application, **When** the application runs, **Then** nothing changes.

---

### User Story 5 - Use it without Spring (Priority: P3)

A developer who assembles the runtime by hand (plain Java, or a future non-Spring host) passes the application's
OpenTelemetry instance to the runtime builder and the transport builder, and gets the same traces and metrics as the starter
produces.

**Why this priority**: the runtime and the transport are framework-neutral by design; observability must not be a
Spring-only feature, but most users get it through the starter.

**Independent Test**: a runtime and in-memory transport assembled by hand with an OpenTelemetry SDK produce the
same spans and metrics for the canonical saga as the Spring application in User Story 4.

**Acceptance Scenarios**:

1. **Given** a hand-assembled runtime given the application's OpenTelemetry instance, **When** sagas run, **Then** the spans
   and metrics equal those of the starter for the same scenario.
2. **Given** a hand-assembled runtime without it, **Then** behaviour is unchanged.

---

### Edge Cases

- **No active trace at publication**: a start event published outside any trace starts a new trace for the saga's
  first unit of work.
- **Telemetry backend down or slow**: emitting telemetry never fails, delays or rolls back a unit of work; telemetry
  that cannot be exported is dropped by the telemetry library's own rules.
- **Business data in telemetry**: association values (an order id) and command or event contents may be personal or
  sensitive. By default spans carry saga type, saga id, step, command type, identities, outcomes and failure type and
  classification; association values are recorded only when switched on; event and command contents and failure
  messages beyond the exception type are never recorded unless switched on.
- **Message stored before the feature**: a message in the store from before the upgrade, or stored with tracing off,
  has no trace context; its delivery starts a new trace and links nothing.
- **Many messages from one unit of work**: each stored message carries the context of the unit of work that stored it;
  a delivered unit of work that consumes several events continues the first and links the others.
- **Retries waiting in process**: the wait between attempts is visible as time inside the unit of work's span, not as a
  gap, and the attempt number is recorded.
- **Duplicate and ignored events**: a duplicate, unmatched or ignored event produces a short span with its handling
  outcome (and is counted), so redelivery storms are visible.
- **Reentrant or in-process events**: an event a step records and the same instance runs in process is part of the
  same unit of work's span, not a separate one.
- **Saga test fixture**: the fixture keeps working with no telemetry setup; a test may register an in-memory telemetry
  setup to assert on spans and metrics.
- **Multiple runtimes or applications sharing a backend**: telemetry is tagged with the saga type and the application's
  own service identity, so two applications with a saga type of the same simple name stay distinguishable.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Saga Forge MUST emit traces and metrics through the OpenTelemetry API: the runtime and the in-memory
  transport take the application's OpenTelemetry instance from their builders and instrument themselves with it. Without
  one, they use OpenTelemetry's no-op instance, which records nothing and costs next to nothing.
- **FR-002**: `saga-forge-core` MUST NOT gain a runtime dependency on any telemetry library (Constitution Principle I).
  The OpenTelemetry API (and its context library) is the only telemetry dependency, and only of the runtime and the
  in-memory transport; trace context crosses core's message-store contract as plain strings.
- **FR-003**: The runtime MUST produce one span per saga unit of work (including compensation units of work and non-saga
  units of work around a command sent outside a saga), naming saga type, saga id, the triggering event type, the steps
  that ran, attempts, the handling outcome and, for failures, the failure type and classification.
- **FR-004**: The runtime MUST produce one span per command handling (in a saga, outside one, or delivered from the
  outbox), as a child of the sending step's span when there is one, with the command type, `CommandId`, the handler and
  whether it succeeded.
- **FR-005**: A unit of work started by code with an active trace MUST continue that trace; a unit of work started by a
  delivery from the message store MUST continue the trace of the unit of work that stored the message (event or outbox
  command), including after a restart.
- **FR-006**: The in-memory transport MUST store the trace context with each message and restore it on delivery;
  messages stored without one MUST still be delivered unchanged.
- **FR-007**: Retried attempts, the wait between them, compensations and their triggers, duplicate, unmatched,
  unexpected and ignored events MUST be visible in traces as specified in User Story 1 and the Edge Cases.
- **FR-008**: The runtime and transport MUST emit metrics for: saga starts and terminal outcomes per saga type and
  outcome; handling outcomes per saga type; step and unit-of-work duration per saga type and step; attempts, retries and
  exhausted retries; compensations run and failed; command handlings and failures per command type; and the message
  store's counts per status and oldest unconsumed age.
- **FR-009**: Metric dimensions MUST be bounded by the registered model and fixed enumerations; no business value (saga
  id, association value, command content) MAY be a metric dimension.
- **FR-010**: Message-store metrics MUST report the same figures as the starter's operator endpoint for the same moment
  (one source, G-008-02).
- **FR-011**: Every runtime diagnostic written inside a unit of work MUST carry the active trace and span identity when
  tracing is on, and MUST be unchanged when it is off.
- **FR-012**: Telemetry MUST never change saga behaviour: a failure to emit or export it MUST NOT fail, delay or roll
  back a unit of work, and with the support absent or switched off, behaviour, outputs and stored data MUST be
  identical to today's (a parity check over the runtime's existing scenarios).
- **FR-013**: By default telemetry MUST NOT record association values, event or command contents, or failure messages;
  each MUST be switchable on separately. Saga ids and command identities MAY be recorded (they are generated, not
  business data).
- **FR-014**: The Spring Boot starter MUST hand the application's OpenTelemetry instance (from Spring Boot's
  OpenTelemetry setup, or Micrometer Tracing's OpenTelemetry bridge) to the runtime and the transport automatically,
  back off for an application's own wiring, and offer properties to switch tracing and metrics off and to switch on each
  optional detail of FR-013.
- **FR-015**: A hand-assembled runtime and transport MUST be able to use the same support through their builders, with
  the same spans and metrics as the starter.
- **FR-016**: Span names, attribute names and metric names MUST follow OpenTelemetry's naming conventions, be namespaced
  for Saga Forge, and be documented as a stable reference.
- **FR-017**: The saga test fixture MUST keep working without any telemetry setup, and MUST allow a test to register
  an in-memory telemetry setup to assert on the spans and metrics a scenario produced.
- **FR-018**: The operator surface MUST stay as it is: the starter's read-only endpoint and health indicator are
  unchanged apart from sharing their message-store figures with the metrics (FR-010). Saga-instance views and write
  actions (redelivering set-aside messages, resolving `REQUIRES_INTERVENTION`) stay deferred (G-008-02).
- **FR-019**: The documentation MUST include a reference of every span, attribute and metric, a Spring Boot setup, a
  plain-Java setup and an example dashboard query for each alert in User Story 2.

### Key Entities

- **Saga unit-of-work span**: one unit of work of one saga instance, its steps, attempts, outcome and failure.
- **Command span**: one handling of one command, with its identity, handler and result.
- **Trace context**: the identity of the trace and parent span that a stored message carries from the unit of work
  that stored it to the one that handles it.
- **Saga metrics**: counters and duration distributions keyed by saga type, step, command type, outcome and
  classification; message-store gauges keyed by message status.
- **Telemetry settings**: whether tracing and metrics are on, and which optional details (association values,
  contents, failure messages) are recorded.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: For the canonical saga placed through a web request, 100% of its units of work, steps and commands
  (including those delivered through the message store, and after a restart) appear in the request's single trace.
- **SC-002**: An operator can tell, from metrics alone, the number of started, completed, failed, compensated and
  intervention-requiring sagas per saga type over any time window, and the current message-store backlog, with figures
  that match the stored state exactly in a controlled test.
- **SC-003**: Metric cardinality is independent of traffic: running 10 or 10,000 orders produces the same set of metric
  series.
- **SC-004**: With the support absent or switched off, the runtime's existing test scenarios produce identical
  outcomes, stored data and messages (0 differences), and with it on, a saga's units of work take no more than 5% longer
  on the reference scenario.
- **SC-005**: A Spring Boot application with an existing telemetry setup gets saga traces and metrics with no extra
  dependency and no code.
- **SC-006**: With default settings, no association value, event or command content or failure message appears in any
  exported telemetry of the reference scenario.
- **SC-007**: A failure to export telemetry (backend unreachable) changes no saga outcome in the reference scenario.
- **SC-008**: A Spring Boot application traced through Micrometer Tracing's OpenTelemetry bridge shows the saga spans
  inside its request traces, exactly as an application using the OpenTelemetry SDK directly.

## Assumptions

- **Audience**: operators and developers running Saga Forge applications in production, who already use an
  OpenTelemetry-compatible backend.
- **What is out of scope**: a programmatic query API over lifecycle history (G-003-24) and process-level state
  (`docs/architecture.md#feature-gaps`); dashboards as shipped artifacts (the docs give example queries); the Kafka
  transport's context propagation (it will follow this feature's message-store contract when it is built); profiling.
- **Logs**: the runtime keeps its existing diagnostics; this feature correlates them with traces but does not change
  their content or ship a log exporter.
- **Sampling**: whether a trace is recorded is the telemetry setup's decision (its sampler); Saga Forge does not sample.
- **Upgrade**: storing trace context with messages may need a schema change for the Postgres message store, applied
  like the earlier upgrade scripts; messages already stored keep working.
- **Constitution**: per G-GOV-01, a focused, stack-neutral dependency may land in the module that needs it, never in
  core. The OpenTelemetry API is the first third-party dependency of the runtime and the in-memory transport; the
  AGENTS.md Module Map ("JDK and core only") is reworded with it.
- **Micrometer**: no Micrometer-specific support ships. Applications on Micrometer Tracing see saga spans through its
  OpenTelemetry bridge; saga metrics go through OpenTelemetry's metrics API and reach a Micrometer-only metrics setup
  only if the application also exports OpenTelemetry metrics (checked during planning).
- **Operations features**: saga-instance views, redelivery and a way out of `REQUIRES_INTERVENTION` belong to a later
  operations feature built on the starter's endpoint (G-008-02, G-006-03).
