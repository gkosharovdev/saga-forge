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
| `saga-forge.outcome-events.enabled` | `true` | Whether a saga that ends records an outcome event (`SagaCompleted`, `SagaFailed` or `SagaCompensated`) with its last unit of work. |
| `saga-forge.application-events.mode` | `in-transaction` | `in-transaction`: recorded events are published as Spring application events inside the unit of work. `after-delivery`: published when the transport delivers them, after the commit. `none`: not published. |
| `saga-forge.transport.workers` | `4` | Delivery threads; messages of one aggregate always use the same one. |
| `saga-forge.transport.max-attempts` | `10` | Failed deliveries before a message is set aside for an operator; for an outbox command a saga sent, also the attempts before its saga is told it failed (`EXHAUSTED`) and compensates. A `FinalFailure` is never retried. |
| `saga-forge.transport.initial-backoff` | `1s` | Wait after a message's first failed delivery, doubled per further failure. |
| `saga-forge.transport.max-backoff` | `1m` | Longest wait between delivery attempts. |
| `saga-forge.transport.lease-duration` | `5m` | How long a leased message is reserved for this instance before another may take it. |
| `saga-forge.transport.poll-interval` | `500ms` | Longest wait between polls when nothing signals new messages. |
| `saga-forge.transport.lease-batch-size` | `100` | Most messages leased at once. |
| `saga-forge.transport.retention` | `7d` | How long consumed messages are kept. |
| `saga-forge.transport.retention-sweep-interval` | `1h` | How often consumed messages older than the retention are removed. |
| `saga-forge.transport.leadership.lease-duration` | `30s` | How long the delivering instance's leadership lasts without renewal; after a crash a standby takes over once it expires. |
| `saga-forge.transport.leadership.renew-interval` | `10s` | How often the delivering instance renews its leadership; shorter than the lease duration. |
| `saga-forge.transport.leadership.check-interval` | `5s` | How often a standby instance checks whether it can take over; shorter than the lease duration. |
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
  `schema-upgrade-005.sql`, `-006.sql`, `-007.sql`, `-010.sql`, `-011.sql`, `-017.sql`, `-013.sql`.
- A **new database** gets `schema.sql` only, through your migration tool (Flyway/Liquibase) or, in development,
  `saga-forge.storage.postgres.initialize-schema=always`. The 0.1.0 `schema.sql` already contains every upgrade.
- The **upgrade scripts** bring a database created by an earlier schema forward, in order; each names the schema it
  applies to in its header comment. Run each once, in one transaction, while no runtime is running. Release notes list
  any new upgrade script.
- `schema-upgrade-011.sql` renames the status `REQUIRES_INTERVENTION` to `COMPENSATION_FAILED` and removes the
  history rows of the dropped `REQUIRES_INTERVENTION` kind. Code that reads statuses by name must use the new name.
- `schema-upgrade-017.sql` adds the message store's due time and the `CANCELLED` status, for deadlines and scheduled
  commands.
- `schema-upgrade-013.sql` (after `-017.sql`) creates the leadership table `saga_message_store_leader` and drops the
  heartbeat table `saga_message_store_consumer`. Stop every instance before applying it.
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
  { "counts": { "CANCELLED": 4, "CONSUMED": 120, "SET_ASIDE": 1, "UNCONSUMED": 3 }, "oldestUnconsumed": "PT2.5S",
    "leadership": { "role": "STANDBY", "relayId": "a91e…",
      "leader": { "holderId": "7f3c…", "holderHost": "orders-1", "fencingToken": 12,
                  "acquiredAt": "2026-11-26T09:00:00Z", "expiresAt": "2026-11-26T09:12:30Z" } } }
  ```

  `role` is this instance's (`ACTIVE` or `STANDBY`); `leader` is the leadership record as last seen, absent before
  any was seen.
- The `sagaforge` health contributor is `DOWN` with a `setAside` detail while any message waits for an operator (a
  delivery that failed `saga-forge.transport.max-attempts` times), `DEGRADED` while no instance has led the message
  store for longer than one leadership lease, and `UP` otherwise. Its details carry `role`, `fencingToken` and, on a
  standby, `leader` as `holderId@holderHost`. Spring Boot leaves `DEGRADED` out of the overall health unless you add
  it to `management.endpoint.health.status.order` (see [Running several instances](#running-several-instances)).
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
| `saga_forge.delivery` | consumer | message the transport delivers | `messaging.system`=`saga_forge.inmemory`, `messaging.operation.type`=`process`, `saga_forge.message.kind`, `saga_forge.payload.type`, `error.type` | `messaging.message.id`, `saga_forge.delivery.attempt`; a failed outbox command: `saga_forge.command.classification` (`final`, `transient`, `unclassified`), and `saga_forge.command.reported`=`true` once its saga was told |
| `saga_forge.event.ignored` | internal | event no saga type reacts to | `saga_forge.event.type` | content\* |

Events on `saga_forge.handle`: `saga_forge.saga.started`, `saga_forge.saga.ended` (`saga_forge.saga.outcome`),
`saga_forge.retry.scheduled` (`saga_forge.attempt.number`, `saga_forge.retry.wait` in ms),
`saga_forge.compensation.started`. When a transport reports a failed outbox command, the runtime's reaction has a
`saga_forge.handle` span of its own, with `saga_forge.command.type`, `saga_forge.compensation.trigger`=`COMMAND_FAILED`
and `saga_forge.failure.classification` (`FINAL` or `EXHAUSTED`), and `saga_forge.handling.outcome` `COMPENSATED`,
`COMPENSATION_FAILED` or `IGNORED`. \* off by default; failure messages (`saga_forge.failure.message` and exception
events) likewise.

Metrics:

| Metric | Instrument | Unit | Attributes |
|---|---|---|---|
| `saga_forge.handle.duration`, `.attempt.duration`, `.step.duration`, `.command.duration`, `.compensation.duration`, `.delivery.duration` | histogram | s | the span's low-cardinality attributes |
| `saga_forge.saga.started` | counter | — | `saga_forge.saga.type` |
| `saga_forge.saga.ended` | counter | — | `saga_forge.saga.type`, `saga_forge.saga.outcome` (`completed:<step>`, `failed`, `compensated`, `compensation_failed`) |
| `saga_forge.event.handled` | counter | — | `saga_forge.saga.type`, `saga_forge.handling.outcome` |
| `saga_forge.retry.scheduled`, `saga_forge.retry.exhausted` | counter | — | `saga_forge.saga.type`, `saga_forge.step` |
| `saga_forge.deadlines` | counter | — | `saga_forge.saga.type`, `saga_forge.deadline.name`, `saga_forge.deadline.event` (`started`, `cancelled`, `expired`, `ignored`) |
| `saga_forge.command.failures` | counter | — | `classification` (`final`, `exhausted`): outbox commands the transport gave up on and reported to their saga |
| `saga_forge.message_store.messages` | gauge | `{message}` | `saga_forge.message.status` |
| `saga_forge.message_store.oldest_unconsumed.age` | gauge | s | — |
| `saga_forge.relay.active` | gauge | — | 1 while this instance's relay leads its message store, else 0 |
| `saga_forge.relay.leadership_changes` | counter | — | `saga_forge.leadership.change` (`acquired`, `lost`, `released`) |

A deadline delivery's `saga_forge.handle` span carries `saga_forge.deadline.name`; a delivery span of a message with a
due time carries `saga_forge.message.not_before` on its first attempt. A deadline that arrives when its instance no
longer waits on it is logged at INFO as `saga.deadline.ignored` with `reason=NOT_RUNNING`, `NOT_OPEN` or `REPLACED`.

Runtime log lines written inside a unit of work carry `traceId=` and `spanId=`.

Example alerts, in PromQL, assuming the OpenTelemetry-to-Prometheus name translation (dots become underscores,
counters gain `_total`, units become suffixes):

```promql
# Sagas failing or failing to compensate, per saga type
sum by (saga_forge_saga_type) (rate(saga_forge_saga_ended_total{saga_forge_saga_outcome=~"failed|compensation_failed"}[5m])) > 0

# Compensations as a share of finished sagas
sum(rate(saga_forge_saga_ended_total{saga_forge_saga_outcome="compensated"}[15m]))
  / sum(rate(saga_forge_saga_ended_total[15m])) > 0.05

# Retries exhausted
sum by (saga_forge_saga_type, saga_forge_step) (increase(saga_forge_retry_exhausted_total[15m])) > 0

# Outbox commands that failed for good and made their saga compensate
sum by (classification) (increase(saga_forge_command_failures_total[15m])) > 0

# Messages set aside for an operator
saga_forge_message_store_messages{saga_forge_message_status="SET_ASIDE"} > 0

# Delivery falling behind
saga_forge_message_store_oldest_unconsumed_age_seconds > 60
```


## Running several instances

Any number of instances can run over one database. Every instance accepts requests, runs units of work and appends
messages; the transports elect one **active** relay that delivers every message, deadline and scheduled command, while
the others are **standby**.

| Event | What happens | Takeover |
|---|---|---|
| Graceful stop of the active instance | It finishes its in-flight deliveries and releases the leadership | within `check-interval` (5 s) |
| Crash, network partition or long pause | Its leadership expires; a standby acquires a newer fencing token, and the store refuses the old one's leases | within `lease-duration + check-interval` (35 s) |
| Database unreachable for the active instance | It stops leasing one `renew-interval` before its lease ends by its own clock | as for a crash |

Delivery stays at least once: a message the old leader held may be delivered again by the new one, so handlers pass
their `CommandId` on. Delivery throughput is that of one instance; partitioned delivery is not supported.

Log events (logger of the transport): `saga.transport.leadership.acquired` (INFO, with `relay`, `host`,
`fencingToken`, `previousHolder`), `saga.transport.leadership.released` (INFO), `saga.transport.leadership.lost`
(WARNING, `cause=renewRefused`, `trustExpired` or `fenced`, and `newHolder` when known) and
`saga.transport.leadership.checkFailed` (WARNING, the store could not be reached; the role does not change by itself).

To make a leaderless store show in the overall health:

```properties
management.endpoint.health.status.order=down,out-of-service,degraded,up,unknown
management.endpoint.health.status.http-mapping.degraded=200
```

Alert on `max(saga_forge.relay.active) == 0` across instances for longer than `lease-duration + check-interval`.

**Why one active relay.** One consumer keeps per-aggregate order and due times simple, and a leadership lease with a
fencing token keeps a paused or partitioned instance from delivering beside the new leader. The design and the prior
art behind it (Kubernetes leases, Kafka epochs, Axon token claims and others) are in the
[architecture notes](../architecture.md#how-other-frameworks-solved-the-same-problems).

## Outcome events

When a saga instance reaches a terminal status, the unit of work that writes the status also records one outcome
event (`io.github.gkosharovdev.sagaforge.core.outcome`):

| Status | Outcome event |
|---|---|
| `COMPLETED` | `SagaCompleted`, with the `@EndSaga` step that ended it |
| `FAILED` | `SagaFailed` with `phase() == STEP`: the failed step, its classification, cause type and attempts |
| `COMPENSATION_FAILED` | `SagaFailed` with `phase() == COMPENSATION`: the compensation that failed, plus `compensationTrigger()` |
| `COMPENSATED` | `SagaCompensated`, with `trigger()`: why compensation started |

Every outcome event carries the saga type, the saga id, the instance's associations and the time. It never carries a
failure message, a `SagaContext` value or a saga field. Its `eventId()` is `sagaId:STATUS` and is the same on every
delivery, so a listener that must act once deduplicates on it.

An outcome event is **not a domain event**: no saga step can take one (the model is rejected at startup), and the
runtime hands a delivered outcome event to no saga. Applications, policies and other modules listen for it.

How reliably it arrives depends on the outbox, like any event the unit of work records:

| Outbox | Stored | Delivered |
|---|---|---|
| In-memory transport (own log) | in the terminal write's transaction | at least once |
| In-memory transport, Spring Modulith interop | in Modulith's registry, same transaction | at least once |
| `ImmediateDispatchOutbox` (the default without a transport) | not stored; dispatched after commit | at most once |
| Saga test fixture | staged in the transaction, released on commit | exactly once, synchronous |

**Listening.** In Spring Boot, outcome events are published as application events by the same rule as recorded events
(`saga-forge.application-events.mode`): use `@TransactionalEventListener` or `@ApplicationModuleListener`, which run
after the commit and never for a rolled-back unit of work; see [recipe 6](recipes.md#6-reacting-to-how-a-saga-ended).
In a plain Java host, register a delivery callback on the in-memory transport; the starter's after-delivery bridge
does exactly that:

```java
public ApplicationEventBridge(InMemoryTransport transport, ApplicationEventPublisher publisher) {
    this.publisher = publisher;
    transport.onDelivered(this::publish);
}
```

Source: `saga-forge-spring-boot-starter/src/main/java/io/github/gkosharovdev/sagaforge/spring/transport/ApplicationEventBridge.java`.

The in-memory transport orders outcome events per instance (ordering key `saga:<sagaId>`).

**Switching them off.** `saga-forge.outcome-events.enabled=false`, `SagaRuntime.Builder.outcomeEvents(false)` or, in
tests, `SagaFixture.Builder.outcomeEvents(false)`.

**Failures of the outbox.** With an `IN_TRANSACTION` outbox (the in-memory transport), a failure to store the outcome
event rolls back the write it belongs to: for a failed saga, the `FAILED` status is then not written, the runtime logs
`saga.instance.failedNotRecorded` and the exception reaches the caller. With an `AFTER_COMMIT` outbox the status stays
written and the failure is listed in the handling result's `outboxFailures()`.

**Failure text.** Outcome events carry no failure message, but logs and spans can (spans only with
`saga-forge.telemetry.record-failure-messages=true`). Mask secrets in your logging backend (Logback `%replace`, Log4j2
`RegexReplacement`) and in the OpenTelemetry Collector (`redaction` or `transform` processor). The `saga_instance` and
lifecycle-history tables keep the raw message.

## Messages set aside

The in-memory transport sets a message aside with one of these reasons, visible in `MessageStoreQueries` and the
Actuator endpoint:

| Reason | When | Saga told? |
|---|---|---|
| `MAX_ATTEMPTS` | delivery failed `saga-forge.transport.max-attempts` times | for an outbox command a saga sent: yes, `EXHAUSTED` |
| `FINAL_FAILURE` | an outbox command's handler threw a `FinalFailure`; not retried | for a command a saga sent: yes, `FINAL` |
| `UNREADABLE: ...` | the payload could not be read back | for an outbox command a saga sent: yes, `FINAL` |

When the saga is told, the message is set aside in the same transaction as the saga's reaction; if the reaction fails,
the message stays and is delivered again after the backoff. A saga that had already ended or was already compensating
is not changed: the runtime logs `saga.command.failure.ignored` (WARNING, with `sagaType`, `sagaId`, `sender`,
`command`, `commandId`, `classification` and `reason`: `missing`, `terminal:<status>`, `compensating`,
`already-compensated`, `not-compensated`, `nothing-committed` or `unknown-sender`). Redelivering a set-aside command
after its saga compensated runs its handler again but does not compensate twice.

## Statuses an operator sees

| `SagaStatus` | Meaning | Action |
|---|---|---|
| `RUNNING` | committed, waiting for the next event | none; a saga waits indefinitely (no timeouts) |
| `COMPLETED` | ended at an `@EndSaga` step (the outcome is its name) | none |
| `COMPENSATING` | compensations are being run | resumes on the next event for the instance |
| `COMPENSATED` | all committed steps undone; the trigger says why (a `@StartCompensation` step, a step's `FINAL` or `EXHAUSTED` failure, or a failed outbox command, `COMMAND_FAILED`) | none |
| `FAILED` | an unclassified failure; committed effects stay in place | investigate; no resume path |
| `COMPENSATION_FAILED` | a compensation failed; the instance shows what was and was not undone | fix by hand; no operator API yet |
| `CREATED` | created, first unit of work not finished (a crash at that moment strands it) | investigate |
