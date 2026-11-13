# Operations

## Properties

All properties of the Spring Boot starter (your IDE completes them from its configuration metadata). The starter's
[README](../../saga-forge-spring-boot-starter/README.md) describes what each assembled piece is and how to replace it
with your own bean.

| Property | Default | Meaning |
|---|---|---|
| `saga-forge.enabled` | `true` | Whether Saga Forge's auto-configuration is active. |
| `saga-forge.scan-packages` | none | Packages scanned for saga model classes in addition to the application's packages. |
| `saga-forge.storage.type` | detected | Which storage module to use when several are on the class path (`postgres`). |
| `saga-forge.storage.postgres.initialize-schema` | `never` | `always` creates Saga Forge's tables in an empty database; `never` fails startup if they are missing. |
| `saga-forge.runtime.max-total-retry-backoff` | `30s` | Longest total wait a step's `@RetryPolicy` may add up to; a longer policy fails startup. |
| `saga-forge.application-events.mode` | `in-transaction` | `in-transaction`: recorded events are published as Spring application events inside the unit of work. `after-delivery`: published when the transport delivers them, after the commit. `none`: not published. |
| `saga-forge.transport.workers` | `4` | Delivery threads; messages of one aggregate always use the same one. |
| `saga-forge.transport.max-attempts` | `10` | Failed deliveries before a message is set aside for an operator. |
| `saga-forge.transport.initial-backoff` | `1s` | Wait after a message's first failed delivery, doubled per further failure. |
| `saga-forge.transport.max-backoff` | `1m` | Longest wait between delivery attempts. |
| `saga-forge.transport.lease-duration` | `5m` | How long a leased message is reserved for this instance before another may take it. |
| `saga-forge.transport.poll-interval` | `500ms` | Longest wait between polls when nothing signals new messages. |
| `saga-forge.transport.lease-batch-size` | `100` | Most messages leased at once. |
| `saga-forge.transport.retention` | `7d` | How long consumed messages are kept. |
| `saga-forge.transport.retention-sweep-interval` | `1h` | How often consumed messages older than the retention are removed. |
| `saga-forge.transport.heartbeat-interval` | `30s` | How often this instance refreshes its heartbeat in the message store. |
| `saga-forge.transport.modulith-interop` | `true` | Whether the transport keeps its messages in Spring Modulith's registry when it is present. |
| `saga-forge.telemetry.enabled` | `true` | Whether the application's OpenTelemetry instance is handed to the runtime and the transport. |
| `saga-forge.telemetry.tracing.enabled` | `true` | Whether saga spans are recorded. |
| `saga-forge.telemetry.metrics.enabled` | `true` | Whether saga metrics are recorded. |
| `saga-forge.telemetry.record-association-values` | `false` | Record association values (an order id, say) on spans. |
| `saga-forge.telemetry.record-contents` | `false` | Record saga event and command contents (`toString`, at most 1,000 characters) on spans. |
| `saga-forge.telemetry.record-failure-messages` | `false` | Record failure messages and exception events on spans; the failure's type is always recorded. |


## Schema and upgrades

- `saga-forge-storage-postgres` ships its DDL as classpath resources under
  `io/github/gkosharovdev/sagaforge/storage/postgres/`: `schema.sql` (the complete current schema) and
  `schema-upgrade-005.sql`, `-006.sql`, `-007.sql`, `-010.sql`.
- A **new database** gets `schema.sql` only, through your migration tool (Flyway/Liquibase) or, in development,
  `saga-forge.storage.postgres.initialize-schema=always`. The 0.1.0 `schema.sql` already contains every upgrade.
- The **upgrade scripts** bring a database created by an earlier schema forward, in order; each names the schema it
  applies to in its header comment. Run each once, in one transaction, while no runtime is running. Release notes list
  any new upgrade script.
- Saga Forge ships no migration tool and never alters existing tables. With `initialize-schema=never` (the default),
  startup fails if tables are missing, naming them.

## Actuator

With `spring-boot-starter-actuator` on the classpath:

```properties
management.endpoints.web.exposure.include=health,sagaforge
```

- `GET /actuator/sagaforge` (read-only) returns the message store's counts per status (capped at 1,000 each) and the
  age of the oldest unconsumed message:

  ```json
  { "counts": { "CONSUMED": 120, "SET_ASIDE": 1, "UNCONSUMED": 3 }, "oldestUnconsumed": "PT2.5S" }
  ```

- The `sagaforge` health contributor is `DOWN` with a `setAside` detail while any message waits for an operator (a
  delivery that failed `saga-forge.transport.max-attempts` times).
- Switch them off with `management.endpoint.sagaforge.enabled=false` and `management.health.sagaforge.enabled=false`.
- There is no endpoint for saga instances and no write action yet; inspect `saga_instance`, `saga_context` and
  `saga_lifecycle_history` directly.

## OpenTelemetry

The runtime and the in-memory transport instrument themselves with the OpenTelemetry API. Without an SDK nothing is
recorded. With Spring Boot, nothing to add if the application already traces with OpenTelemetry
(`spring-boot-starter-opentelemetry`, or Micrometer Tracing's `micrometer-tracing-bridge-otel`): the starter passes the
application's `OpenTelemetry` bean on. Each unit of work, step, command and compensation becomes a span in the trace of
the code that published the start event, also across the message store and restarts.

**Metrics** are recorded through OpenTelemetry's metrics API. Spring Boot exports them only when the application
defines an `SdkMeterProvider` bean with an exporter, for example with `opentelemetry-exporter-otlp`:

```java
@Bean
SdkMeterProvider sdkMeterProvider(Resource resource) {
    return SdkMeterProvider.builder().setResource(resource)
            .registerMetricReader(PeriodicMetricReader.builder(OtlpHttpMetricExporter.getDefault()).build())
            .build();
}
```

Source: `saga-forge-spring-boot-starter/README.md` ("With OpenTelemetry"). Without it, spans are exported and saga
metrics are dropped.

**Privacy**: spans carry saga types, step and command names, generated ids, outcomes and failure types. Association
values, contents and failure messages are recorded only when switched on (`saga-forge.telemetry.record-*`).

### Telemetry reference

Spans (instrumentation scope `io.github.gkosharovdev.sagaforge`):

| Span | Kind | One per | Attributes (always) | Details |
|---|---|---|---|---|
| `saga_forge.handle` | internal | saga type and instance an event reaches | `saga_forge.saga.type`, `saga_forge.event.type`, `saga_forge.handling.outcome`, `saga_forge.saga.started`, `saga_forge.failure.classification`, `error.type` | `saga_forge.saga.id`; association value\*, content\* |
| `saga_forge.attempt` | internal | attempt of a unit of work | `saga_forge.saga.type`, `saga_forge.attempt.result`, `saga_forge.failure.classification`, `error.type` | `saga_forge.saga.id`, `saga_forge.unit_of_work.id`, `saga_forge.attempt.number` |
| `saga_forge.step` | internal | step run | `saga_forge.saga.type`, `saga_forge.step`, `error.type` | `saga_forge.saga.id` |
| `saga_forge.command` | internal | command handling | `saga_forge.command.type`, `saga_forge.command.delivery`, `saga_forge.command.result`, `error.type` | `saga_forge.command.id`, `saga_forge.step`, `saga_forge.saga.id`, content\* |
| `saga_forge.compensation` | internal | compensation | `saga_forge.saga.type`, `saga_forge.step`, `saga_forge.compensation.status`, `saga_forge.compensation.trigger` | `saga_forge.saga.id`, `saga_forge.unit_of_work.id` |
| `saga_forge.delivery` | consumer | message the transport delivers | `messaging.system`=`saga_forge.inmemory`, `messaging.operation.type`=`process`, `saga_forge.message.kind`, `saga_forge.payload.type`, `error.type` | `messaging.message.id`, `saga_forge.delivery.attempt` |
| `saga_forge.event.ignored` | internal | event no saga type reacts to | `saga_forge.event.type` | content\* |

Events on `saga_forge.handle`: `saga_forge.saga.started`, `saga_forge.saga.ended` (`saga_forge.saga.outcome`),
`saga_forge.retry.scheduled` (`saga_forge.attempt.number`, `saga_forge.retry.wait` in ms),
`saga_forge.compensation.started`. \* off by default; failure messages (`saga_forge.failure.message` and exception
events) likewise.

Metrics:

| Metric | Instrument | Unit | Attributes |
|---|---|---|---|
| `saga_forge.handle.duration`, `.attempt.duration`, `.step.duration`, `.command.duration`, `.compensation.duration`, `.delivery.duration` | histogram | s | the span's low-cardinality attributes |
| `saga_forge.saga.started` | counter | — | `saga_forge.saga.type` |
| `saga_forge.saga.ended` | counter | — | `saga_forge.saga.type`, `saga_forge.saga.outcome` (`completed:<step>`, `failed`, `compensated`, `requires_intervention`) |
| `saga_forge.event.handled` | counter | — | `saga_forge.saga.type`, `saga_forge.handling.outcome` |
| `saga_forge.retry.scheduled`, `saga_forge.retry.exhausted` | counter | — | `saga_forge.saga.type`, `saga_forge.step` |
| `saga_forge.message_store.messages` | gauge | `{message}` | `saga_forge.message.status` |
| `saga_forge.message_store.oldest_unconsumed.age` | gauge | s | — |

Runtime log lines written inside a unit of work carry `traceId=` and `spanId=`.

Example alerts, in PromQL, assuming the OpenTelemetry-to-Prometheus name translation (dots become underscores,
counters gain `_total`, units become suffixes):

```promql
# Sagas failing or needing intervention, per saga type
sum by (saga_forge_saga_type) (rate(saga_forge_saga_ended_total{saga_forge_saga_outcome=~"failed|requires_intervention"}[5m])) > 0

# Compensations as a share of finished sagas
sum(rate(saga_forge_saga_ended_total{saga_forge_saga_outcome="compensated"}[15m]))
  / sum(rate(saga_forge_saga_ended_total[15m])) > 0.05

# Retries exhausted
sum by (saga_forge_saga_type, saga_forge_step) (increase(saga_forge_retry_exhausted_total[15m])) > 0

# Messages set aside for an operator
saga_forge_message_store_messages{saga_forge_message_status="SET_ASIDE"} > 0

# Delivery falling behind
saga_forge_message_store_oldest_unconsumed_age_seconds > 60
```


## Statuses an operator sees

| `SagaStatus` | Meaning | Action |
|---|---|---|
| `RUNNING` | committed, waiting for the next event | none; a saga waits indefinitely (no timeouts) |
| `COMPLETED` | ended at an `@EndSaga` step (the outcome is its name) | none |
| `COMPENSATING` | compensations are being run | resumes on the next event for the instance |
| `COMPENSATED` | all committed steps undone | none |
| `FAILED` | an unclassified failure; committed effects stay in place | investigate; no resume path |
| `REQUIRES_INTERVENTION` | a compensation failed; the instance shows what was and was not undone | fix by hand; no operator API yet |
| `CREATED` | created, first unit of work not finished (a crash at that moment strands it) | investigate |
