# Quickstart: Observability with OpenTelemetry

Validation scenarios for 010. Contracts: [instrumentation.md](contracts/instrumentation.md),
[telemetry-reference.md](contracts/telemetry-reference.md), [message-context.md](contracts/message-context.md).

## Prerequisites

- Java 21, Maven; Podman or Docker for scenarios 4 and 6 (`-Pcontainer-tests`).

## Scenarios

1. **No telemetry, no change** (FR-012, SC-004): the existing suites pass unchanged; `TelemetryParityPropertyTest`
   shows `OpenTelemetry.noop()` and an SDK on in-memory exporters give identical outcomes, stored instances and
   messages.
2. **Span tree** (US1): over the canonical saga with an SDK on `InMemorySpanExporter`, `handle` → `attempt` → `step` →
   `command` for `handleOrderCreated` / `ProcessPaymentCommand`, a second attempt after a transient payment timeout with
   a `retry.scheduled` event, and `compensation` for `compensatePayment` after `rejectOrder`; default settings export no
   association value, content or failure message (SC-006).
3. **Metrics** (US2, SC-003): with `InMemoryMetricReader`, 10 orders with 2 out of stock give the counters of User
   Story 2; 10 and 1,000 orders give the same set of series.
4. **Context through the message store** (FR-005, FR-006, SC-001): with the in-memory transport on Postgres, a root span
   publishing `OrderCreatedEvent` and a restart between deliveries still yields one trace; a message stored without
   context starts a new trace.
5. **Logs** (US3): diagnostics inside a unit of work carry `traceId=`/`spanId=`; with `noop()` they are unchanged.
6. **Spring Boot** (US4, SC-005, SC-008): `saga-forge-example-spring` with Micrometer Tracing's OpenTelemetry bridge
   and an in-memory exporter, one web request: the saga spans are children of the request span and saga metrics are
   exported; `saga-forge.telemetry.tracing.enabled=false` removes the spans only.
7. **Faulty instrumentation** (SC-007): a command whose `toString` throws, with contents switched on, changes no saga
   outcome.
8. **Plain Java and the fixture** (US5, FR-017): a hand-assembled runtime and transport, and a `SagaFixture` given an
   SDK, produce the spans of scenario 2.

## Expected outcome

All scenarios pass in `mvn -B clean verify` and, for 4 and 6, `-Pcontainer-tests`.
