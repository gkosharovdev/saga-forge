# Contract: Instrumentation through the OpenTelemetry API

The runtime and the in-memory transport instrument themselves with `io.opentelemetry:opentelemetry-api`. Core has no
telemetry dependency (Constitution Principle I, FR-002).

## Builders

```java
SagaRuntime runtime = SagaRuntime.builder()
        ...
        .openTelemetry(openTelemetry)                        // default OpenTelemetry.noop()
        .telemetry(SagaTelemetrySettings.defaults()          // optional
                .recordAssociationValues(false)
                .recordContents(false)
                .recordFailureMessages(false))
        .build();

InMemoryTransport transport = InMemoryTransport.builder()
        ...
        .openTelemetry(openTelemetry)                        // the same instance
        .telemetry(settings)
        .build();
```

`SagaTelemetrySettings` lives in core (`core.telemetry`): a plain immutable value class with no telemetry type, so the
runtime and the transport share it. It holds `tracing` (default on), `metrics` (default on) and the three detail
switches (default off). With `tracing` off a module uses `TracerProvider.noop()`; with `metrics` off,
`MeterProvider.noop()`.

Instrumentation scope: name `io.github.gkosharovdev.sagaforge`, version = the Saga Forge version.

## Obligations of the runtime and the transport

1. Spans are started and ended in matched pairs, in `try`/`finally`, on the thread that started them, and made current
   (`span.makeCurrent()`) for the code they cover; nesting is as in research §2 and
   [telemetry-reference.md](telemetry-reference.md).
2. A span that covers a failure records it with `recordException` only when failure messages are switched on, always
   sets `error.type` and status `ERROR`.
3. Low-cardinality attributes take values from the registered model and fixed enumerations only; ids are span
   attributes only; association values, contents and failure messages only when switched on (FR-009, FR-013).
4. Saga Forge's own instrumentation code (attribute building, `toString` of contents) is guarded: a
   `RuntimeException` is logged once per kind (`saga.telemetry.failed`) and ignored (FR-012).
5. With `OpenTelemetry.noop()` the behaviour, outputs and log lines are unchanged (Property 37).
6. The transport injects `Context.current()` into each stored message with the configured `TextMapPropagator` and
   extracts it on delivery as the parent of a `CONSUMER` span ([message-context.md](message-context.md)).

## Formal spec

`.kiro/specs/saga-forge-framework/design.md` gains **Property 37 (telemetry)**: for any scenario, spans are started and
ended in matched, properly nested pairs, a failing instrumentation step changes nothing, and with `OpenTelemetry.noop()`
the runtime is indistinguishable from one built before 010.
