# Contract: starter, Actuator `sagas` endpoint, properties, telemetry

## Properties (`SagaForgeProperties`, documented in the starter README; `ReadmePropertiesTest`)

| Property | Default | Maps to |
|---|---|---|
| `saga-forge.recovery.enabled` | `true` | `RecoverySettings.enabled` |
| `saga-forge.recovery.interval` | `1m` | `interval` |
| `saga-forge.recovery.stall-age` | `5m` | `stallAge` |
| `saga-forge.recovery.batch-size` | `100` | `batchSize` |
| `saga-forge.recovery.abandonment-age` | `15m` | `abandonmentAge` |
| `saga-forge.recovery.abandoned-start-policy` | `report` | `REPORT` / `FAIL` |
| `saga-forge.operations.expose-context-values` | `false` | context values over HTTP (FR-035) |
| `saga-forge.telemetry.instance-count-interval` | `30s` | `SagaTelemetrySettings.instanceCountInterval` |

Invalid values (zero durations, batch size out of range) fail startup through a `FailureAnalyzer` naming the property.

`SagaRuntimeLifecycle.stop()` calls `SagaRuntime.stop()`.

## Endpoint `sagas` (`@Endpoint(id = "sagas", defaultAccess = Access.READ_ONLY)`)

Registered when Actuator is on the class path and a `SagaRuntime` bean exists (no transport needed); backs off for an
application bean of the same type.

| Operation | Request | Response |
|---|---|---|
| list (read) | `GET /actuator/sagas?status=FAILED&status=COMPENSATION_FAILED&type=OrderProcessingSaga&modifiedBefore=…&modifiedAfter=…&limit=50&cursor=…` | `200 {instances:[…], next:"token"|null, counts:{byStatus:{…}, compensationFailedUnresolved:n, failedUnresolved:n}}`; bad parameter → 400 |
| view (read) | `GET /actuator/sagas/{sagaId}` | `200 {instance:{…}, history:[…]}` (+ `context` only if exposed); unknown → 404 |
| repair (write) | `POST /actuator/sagas/{sagaId}/{action}` body `{"reason":"…"}`, `action` ∈ `resume-compensation`, `compensate`, `resolve` | `200 {result:"APPLIED", instance:{…}}`; not applied → `409 {result:"NOT_APPLIED", reason:"WRONG_STATUS", status:"COMPLETED"}`; no principal → 401; blank reason or unknown action → 400 |

Instances are rendered as JSON through the application's `ObjectMapper` (Actuator's) from `SagaInstance`, with `state`
omitted.

Test cases (`SagasEndpointTest` with `ApplicationContextRunner` and `WebTestClient`-free invocation through the endpoint
bean; `SagasEndpointWebContainerTest` over Spring Security in the Spring example):

| # | Case | Expectation |
|---|---|---|
| E1 | defaults | list and view work; write operation absent (Boot reports 404/405 for POST) |
| E2 | `management.endpoint.sagas.access=unrestricted`, user `alice` | resume applies; history operator `alice` |
| E3 | access unrestricted, anonymous | 401, no change |
| E4 | context values not exposed | view has no `context` key |
| E5 | `expose-context-values=true` | `context` present |
| E6 | no transport bean | `sagas` endpoint still registered |
| E7 | health | `sagaforge` health details include `instances.compensationFailed` and `instances.failedUnresolved`; status unchanged by them |

## Telemetry (runtime; verified with the OpenTelemetry SDK testing exporter)

| Signal | Name | Attributes |
|---|---|---|
| span | `saga_forge.operation` | `saga_forge.operation.action`, `saga_forge.operation.result`, `saga_forge.saga.type`, `saga_forge.saga.id` |
| counter | `saga_forge.operations` | `action`, `result` |
| counter | `saga_forge.recovery` | `kind` = `compensation_resumed` \| `start_reported` \| `start_failed` \| `error` |
| gauge | `saga_forge.instances` | `saga_forge.saga.status`, `saga_forge.resolved` |
| span attr | `saga_forge.start.recovered` on `saga_forge.handle` | `true` when a stranded start was adopted |

Log events (`System.Logger`, diagnostics keys as in 010): `saga.operation.applied`, `saga.operation.notApplied`,
`saga.recovery.compensationResumed`, `saga.recovery.startAbandoned`, `saga.recovery.startFailed`,
`saga.recovery.failed`, `saga.start.recovered`.
