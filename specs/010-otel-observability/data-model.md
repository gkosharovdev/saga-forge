# Data Model: Observability with OpenTelemetry

No saga data changes. Telemetry is emitted through the OpenTelemetry API, not stored, except the trace context a
message carries.

## Spans

| Field | Meaning |
|---|---|
| name | `saga_forge.handle`, `.attempt`, `.step`, `.command`, `.compensation`, `.delivery`, `.event.ignored` |
| kind | `INTERNAL`; `CONSUMER` for `delivery` |
| parent | the current span, or the context extracted from a stored message |
| attributes | low-cardinality (also on metrics) or details (spans only); details of sensitive kinds only when switched on |
| events | retry scheduled, saga started/ended, compensation started |
| status | `ERROR` with `error.type` on failure |

Reference: [contracts/telemetry-reference.md](contracts/telemetry-reference.md).

## Trace context (message store)

| Field | Meaning |
|---|---|
| traceContext | `Map<String, String>` written by the configured `TextMapPropagator` (W3C `traceparent`, `tracestate`, baggage); empty when none |

Stored with `NewMessage`, returned on `StoredMessage`; Postgres column `trace_context JSONB NULL`.

## SagaTelemetrySettings (core, `core.telemetry`)

| Field | Default | Meaning |
|---|---|---|
| tracing | on | record spans |
| metrics | on | record counters, gauges and duration histograms |
| recordAssociationValues | off | add association values to spans |
| recordContents | off | add event/command `toString` (≤ 1,000 chars) to spans |
| recordFailureMessages | off | add failure messages and exception events to spans |

## Validation rules

- Low-cardinality attribute values come from the registered model or fixed enumerations (FR-009).
- A switched-off detail is never set on a span (FR-013).
- Every span is ended once; Saga Forge's own instrumentation failures are swallowed and logged once per kind (FR-012).
