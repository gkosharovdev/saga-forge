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
| `saga-forge.storage.postgres.initialize-schema` | `never` | `migrate` applies Saga Forge's versioned migrations with Flyway; `validate` checks the schema and fails on a difference; `always` creates the tables in an empty database; `never` fails startup if they are missing. See [Database schema](schema.md). |
| `saga-forge.storage.postgres.migration.history-table` | `saga_forge_schema_history` | Saga Forge's own Flyway history table. |
| `saga-forge.storage.postgres.migration.baseline-version` | unset | The version an existing database without Saga Forge's history is at; replaces detection. |
| `saga-forge.runtime.max-total-retry-backoff` | `30s` | Longest total wait a step's `@RetryPolicy` may add up to; a longer policy fails startup. |
| `saga-forge.outcome-events.enabled` | `true` | Whether a saga that ends records an outcome event (`SagaCompleted`, `SagaFailed` or `SagaCompensated`) with its last unit of work. |
| `saga-forge.application-events.mode` | `in-transaction` | `in-transaction`: recorded events, and events published through `EventPublisher`, are published as Spring application events inside the transaction. `after-delivery` (deprecated, refused while the inbound route is on): published when the transport delivers them, after the commit. `none`: not published. |
| `saga-forge.application-events.inbound.enabled` | `true` | Whether events published through Spring's `ApplicationEventPublisher` (also Spring Data `@DomainEvents`) whose type a saga has a step for are stored for sagas, in the publishing transaction. |
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
| `saga-forge.transport.modulith-interop` | `true` | Whether the transport keeps its messages in Spring Modulith's registry when it is present; not set and a saga declares a deadline, Saga Forge's own log is kept. |
| `saga-forge.telemetry.enabled` | `true` | Whether the application's OpenTelemetry instance is handed to the runtime and the transport. |
| `saga-forge.telemetry.tracing.enabled` | `true` | Whether saga spans are recorded. |
| `saga-forge.telemetry.metrics.enabled` | `true` | Whether saga metrics are recorded. |
| `saga-forge.telemetry.record-association-values` | `false` | Record association values (an order id, say) on spans. |
| `saga-forge.telemetry.record-contents` | `false` | Record saga event and command contents (`toString`, at most 1,000 characters) on spans. |
| `saga-forge.telemetry.record-failure-messages` | `false` | Record failure messages and exception events on spans; the failure's type is always recorded. |
| `saga-forge.telemetry.instance-count-interval` | `30s` | How often the `saga_forge.instances` gauge recounts the sagas by status. |
| `saga-forge.recovery.enabled` | `true` | Whether the runtime runs recovery sweeps on its own. |
| `saga-forge.recovery.interval` | `1m` | The time between two sweeps. |
| `saga-forge.recovery.stall-age` | `5m` | How long a compensating saga stays untouched before a sweep resumes it. |
| `saga-forge.recovery.batch-size` | `100` | Most sagas of each kind one sweep handles (1 to 1,000). |
| `saga-forge.recovery.abandonment-age` | `15m` | How long a created saga with no completed step waits before a sweep handles it. |
| `saga-forge.recovery.abandoned-start-policy` | `report` | `report` logs and counts an abandoned start; `fail` ends it `FAILED`. |
| `saga-forge.operations.expose-context-values` | `false` | Whether the `sagas` endpoint shows a saga's context values; they may hold personal data. |


## Schema and upgrades

With Flyway, `initialize-schema=migrate` applies everything below for you, as versioned migrations: see
[Database schema](schema.md). The scripts stay for hosts that apply the DDL themselves:

- `saga-forge-storage-postgres` ships its DDL as classpath resources under
  `io/github/gkosharovdev/sagaforge/storage/postgres/`: `schema.sql` (the complete current schema) and
  `schema-upgrade-005.sql`, `-006.sql`, `-007.sql`, `-010.sql`, `-011.sql`, `-017.sql`, `-013.sql`, `-015.sql`.
- A **new database** gets `schema.sql` only, through your migration tool (Flyway/Liquibase) or, in development,
  `saga-forge.storage.postgres.initialize-schema=always`. The current `schema.sql` already contains every upgrade.
- The **upgrade scripts** bring a database created by an earlier schema forward, in order; each names the schema it
  applies to in its header comment. Run each once, in one transaction, while no runtime is running. Release notes list
  any new upgrade script.
- `schema-upgrade-011.sql` renames the status `REQUIRES_INTERVENTION` to `COMPENSATION_FAILED` and removes the
  history rows of the dropped `REQUIRES_INTERVENTION` kind. Code that reads statuses by name must use the new name.
- `schema-upgrade-017.sql` adds the message store's due time and the `CANCELLED` status, for deadlines and scheduled
  commands.
- `schema-upgrade-013.sql` (after `-017.sql`) creates the leadership table `saga_message_store_leader` and drops the
  heartbeat table `saga_message_store_consumer`. Stop every instance before applying it.
- `schema-upgrade-015.sql` (after `-013.sql`) adds an instance's `resolution` and `compensation_round`, the operator
  columns of `saga_lifecycle_history` and the indexes the instance listing and the recovery sweeps use.
- Without `migrate`, Saga Forge never alters existing tables. With `initialize-schema=never` (the default), startup
  fails if tables are missing, naming them; `validate` also fails on a missing or different column or constraint.

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
- **Schema version**: with Postgres storage, the payload also has a `schema` object with the configured mode, the
  version in Saga Forge's history (with `migrate`) and the startup check's result:
  `"schema": { "mode": "migrate", "version": "V5 (saga operations)", "check": "PASSED", "failures": 0, "warnings": 0 }`.
  See [Database schema](schema.md).
- The `sagaforge` health contributor is `DOWN` with a `setAside` detail while any message waits for an operator (a
  delivery that failed `saga-forge.transport.max-attempts` times), `DEGRADED` while no instance has led the message
  store for longer than one leadership lease, and `UP` otherwise. Its details carry `role`, `fencingToken` and, on a
  standby, `leader` as `holderId@holderHost`. Spring Boot leaves `DEGRADED` out of the overall health unless you add
  it to `management.endpoint.health.status.order` (see [Running several instances](#running-several-instances)).
- Switch them off with `management.endpoint.sagaforge.enabled=false` and `management.health.sagaforge.enabled=false`.
- The `sagas` endpoint lists, shows and repairs saga instances; see [Operating saga instances](#operating-saga-instances).

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
| `saga_forge.operation` | internal | repair | `saga_forge.saga.type`, `saga_forge.operation.action`, `saga_forge.operation.result` | `saga_forge.saga.id`, `saga_forge.operator` |

Events on `saga_forge.handle`: `saga_forge.saga.started`, `saga_forge.saga.ended` (`saga_forge.saga.outcome`),
`saga_forge.retry.scheduled` (`saga_forge.attempt.number`, `saga_forge.retry.wait` in ms),
`saga_forge.compensation.started`. A start step that adopted a stranded `CREATED` instance sets `saga_forge.start.recovered`=`true` on its `saga_forge.handle` span. When a transport reports a failed outbox command, the runtime's reaction has a
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
| `saga_forge.operations` | counter | — | `saga_forge.operation.action` (`resume-compensation`, `compensate`, `resolve`), `saga_forge.operation.result` (`APPLIED` or the reason it was not) |
| `saga_forge.recovery` | counter | — | `saga_forge.recovery.kind` (`compensation_resumed`, `start_reported`, `start_failed`, `error`) |
| `saga_forge.instances` | gauge | `{saga}` | `saga_forge.saga.status`, `saga_forge.resolved`; recounted every `saga-forge.telemetry.instance-count-interval` |

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
In a plain Java host, register a delivery callback on the in-memory transport; the starter's deprecated after-delivery
bridge does exactly that:

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
| `COMPENSATING` | compensations are being run | resumes on the next event, or by a recovery sweep once it is untouched for `stall-age`; `resume-compensation` |
| `COMPENSATED` | all committed steps undone; the trigger says why (a `@StartCompensation` step, a step's `FINAL` or `EXHAUSTED` failure, or a failed outbox command, `COMMAND_FAILED`) | none |
| `FAILED` | an unclassified failure; committed effects stay in place | investigate, then `compensate` or `resolve`; no retry of the failed step yet |
| `COMPENSATION_FAILED` | a compensation failed; the instance shows what was and was not undone | fix the cause, then `resume-compensation`; or undo by hand and `resolve` |
| `CREATED` | created, first unit of work not finished (a crash at that moment strands it) | a redelivered start event adopts it; a sweep reports it after `abandonment-age`; `compensate` |

## Operating saga instances

Every runtime has an operations API, `SagaRuntime.operations()` (`SagaOperations`), and the starter exposes it as the
`sagas` Actuator endpoint. It finds instances, repairs one at a time and runs recovery sweeps.

### Finding instances

- `list(SagaInstanceQuery)` returns one page, oldest modified first, filtered by status, saga type and modification
  time, with a `PageCursor` for the next page (`limit` 1 to 500, 50 by default). Pages are read one by one, so an
  instance that changes between two pages may appear on both or neither.
- `find(sagaId)`, `findByAssociation(name, value)`, `history(sagaId)` (the lifecycle history, oldest first),
  `context(sagaId)` and `count(statuses)` (per status, plus the `FAILED` and `COMPENSATION_FAILED` sagas no operator
  has resolved).

### The three repairs

Each repair takes an `OperatorRequest` (who, and why; both required) and returns `RepairResult.Applied` with the
instance after the repair, or `RepairResult.NotApplied` with the reason (`NOT_FOUND`, `WRONG_STATUS`,
`ALREADY_RESOLVED`) and the status it found. A repair runs under the instance's lock and re-reads the instance first,
so two operators, or an operator and a sweep, never repair the same instance twice.

| Repair | Applies to | Effect |
|---|---|---|
| `resumeCompensation` | `COMPENSATION_FAILED`, or `COMPENSATING` | starts a new compensation round: failed compensations run again, compensated ones do not; ends `COMPENSATED` (outcome event id `sagaId:COMPENSATED:r<round>`) or `COMPENSATION_FAILED` again |
| `compensate` | `CREATED`, `RUNNING`, `FAILED` | compensates every committed step, most recent first; the trigger is `OPERATOR_REQUEST` with the request |
| `markResolved` | `FAILED`, `COMPENSATION_FAILED` | records that the saga was handled by hand; the status stays, the unresolved counts drop |

Every repair writes a lifecycle entry (`COMPENSATION_RESUMED`, `COMPENSATION_STARTED` or `RESOLVED`) naming the
operator and the reason. A compensation that runs again must be safe to run again: pass its `CommandId` on as the
idempotency key.

In a fixture test, `whenOperator` runs a repair and delivers what it sent
(`saga-forge-example/src/test/java/io/github/gkosharovdev/sagaforge/example/OrderProcessingSagaOperationsFixtureTest.java`):

```java
    fixture
        .whenOperator(
            operations ->
                operations.resumeCompensation(
                    sagaId, new OperatorRequest("alice", "provider back, refund retried")))
        .instance(OrderProcessingSaga.class, "o-1")
        .expectStatus(SagaStatus.COMPENSATED)
        .expectCommands(new RefundPaymentCommand(sagaId, "payment-1", AMOUNT))
        .expectHistory(
            SagaLifecycleEventKind.COMPENSATION_FAILED,
            SagaLifecycleEventKind.COMPENSATION_RESUMED,
            SagaLifecycleEventKind.COMPENSATED)
        .expectOutcomeEvent(
            SagaCompensated.class,
            compensated -> compensated.eventId().equals(sagaId + ":COMPENSATED:r1"));
```

`Then.expectResolved()` and `expectNotResolved()` check a resolution.

### Recovery sweeps

With the starter, every application instance sweeps once a minute (`saga-forge.recovery.*`); a runtime assembled by
hand sweeps only with `SagaRuntime.Builder.recovery(RecoverySettings.enabledWithDefaults())`, and `recover()` runs one
sweep on demand. A sweep:

- resumes the compensation of a saga `COMPENSATING` and untouched for `stall-age` (an instance whose compensation was
  interrupted and that no event reaches);
- handles a saga still `CREATED` with no completed step after `abandonment-age` (its first unit of work never
  committed): with `abandoned-start-policy=report` (the default) it logs `saga.recovery.startAbandoned` once per
  process and counts it; with `fail` it ends it `FAILED`.

`report` is the default because a start ended `FAILED` no longer matches its start event: a redelivered start event
would then start a second saga. Left `CREATED`, the instance is adopted by a redelivered start event, which runs the
start step on it and records `START_RECOVERED`. Choose `fail` when the start event will not come again.

Sweeps on several instances are safe without leadership: each repair re-checks the instance under its lock. Each
sweep handles at most `batch-size` sagas of each kind; the rest wait for the next sweep.

### The `sagas` endpoint

```properties
management.endpoints.web.exposure.include=health,sagaforge,sagas
```

- `GET /actuator/sagas?status=FAILED&status=COMPENSATION_FAILED&type=…&modifiedBefore=…&modifiedAfter=…&limit=50&cursor=…`
  returns one page, the `next` cursor and the counts per status. A bad parameter is a 400.
- `GET /actuator/sagas/{sagaId}` returns the instance and its history; 404 if unknown. The saga's business state is
  never shown, its context values only with `saga-forge.operations.expose-context-values=true`.
- `POST /actuator/sagas/{sagaId}/{action}` with `{"reason": "…"}`, where `action` is `resume-compensation`,
  `compensate` or `resolve`. The authenticated principal is the operator; without one the answer is 401. A repair
  that does not apply is a 409 with the reason and the status.

The write operation is off until `management.endpoint.sagas.access=unrestricted`. Only allow it behind Spring
Security, with a role for operators
(`saga-forge-example-spring/src/test/java/io/github/gkosharovdev/sagaforge/example/spring/SagasEndpointWebContainerTest.java`):

```java
    @Bean
    SecurityFilterChain actuatorSecurity(HttpSecurity http) throws Exception {
      http.authorizeHttpRequests(
              requests ->
                  requests
                      .requestMatchers(HttpMethod.POST, "/actuator/sagas/**")
                      .hasRole("OPERATOR")
                      .anyRequest()
                      .permitAll())
          .httpBasic(Customizer.withDefaults())
          .csrf(csrf -> csrf.disable());
      return http.build();
    }
```

The `sagaforge` health contributor carries `instances.compensationFailed` and `instances.failedUnresolved`; they never
change its status. Telemetry: a `saga_forge.operation` span and the `saga_forge.operations` counter per repair (action
and result), the `saga_forge.recovery` counter per sweep result, and the `saga_forge.instances` gauge per status.
