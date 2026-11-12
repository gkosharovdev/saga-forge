# Contract: Telemetry reference (spans, attributes, metrics)

Stable names (FR-016); documented again in the README's telemetry reference.

## Spans

| Name | Span kind | Started | Low-cardinality attributes | Details |
|---|---|---|---|---|
| `saga_forge.handle` | internal | per saga type and instance an event reaches | `saga_forge.saga.type`, `saga_forge.event.type`, `saga_forge.handling.outcome` (at stop), `saga_forge.saga.started` | `saga_forge.saga.id`, association value* |
| `saga_forge.attempt` | internal | per attempt of a unit of work | `saga_forge.saga.type`, `saga_forge.attempt.result` (`committed`, `rolled_back`), `saga_forge.failure.classification` | `saga_forge.saga.id`, `saga_forge.unit_of_work.id`, `saga_forge.attempt.number` |
| `saga_forge.step` | internal | per step run | `saga_forge.saga.type`, `saga_forge.step` | `saga_forge.saga.id` |
| `saga_forge.command` | internal | per command handling | `saga_forge.command.type`, `saga_forge.command.delivery` (`in_unit_of_work`, `outbox`), `saga_forge.command.result` (`handled`, `failed`, `skipped`) | `saga_forge.command.id`, `saga_forge.step`, content* |
| `saga_forge.compensation` | internal | per compensation unit of work | `saga_forge.saga.type`, `saga_forge.step` (the compensated step), `saga_forge.compensation.status`, `saga_forge.compensation.trigger` | `saga_forge.saga.id`, `saga_forge.unit_of_work.id` |
| `saga_forge.delivery` | consumer | per message the transport delivers | `messaging.system` = `saga_forge.inmemory`, `messaging.operation.type` = `process`, `saga_forge.message.kind` (`event`, `command`), `saga_forge.payload.type` | `messaging.message.id`, `saga_forge.delivery.attempt` |
| `saga_forge.event.ignored` | internal | an event no saga type reacts to | `saga_forge.event.type` | content* |

Failed spans carry `error.type` (the exception's class name) and, if switched on, the failure message*.
Events on `saga_forge.handle`: `saga_forge.saga.started`, `saga_forge.retry.scheduled` (`attempt.number`,
`retry.wait` in ms), `saga_forge.saga.ended` (`saga_forge.saga.outcome`), `saga_forge.compensation.started`
(`trigger`).

`*` optional details, off by default (FR-013): association values, contents (`toString`, ≤ 1,000 chars), failure
messages.

Value sets: `saga_forge.handling.outcome` = `HandlingOutcome` names; `saga_forge.saga.outcome` = `completed:<step>`,
`failed`, `compensated`, `requires_intervention`; `saga_forge.failure.classification` = `FailureClassification` names;
`saga_forge.compensation.status` = `CompensationStatus` names; `saga_forge.compensation.trigger` =
`CompensationTriggerKind` names. Types are fully qualified class names.

## Metrics

| Name | Instrument | Unit | Attributes |
|---|---|---|---|
| `saga_forge.handle.duration` | histogram | s | as the span's low-cardinality attributes, `error.type` |
| `saga_forge.attempt.duration` | histogram | s | 〃 |
| `saga_forge.step.duration` | histogram | s | 〃 |
| `saga_forge.command.duration` | histogram | s | 〃 |
| `saga_forge.compensation.duration` | histogram | s | 〃 |
| `saga_forge.delivery.duration` | histogram | s | 〃 |
| `saga_forge.saga.started` | counter | `{saga}` | `saga_forge.saga.type` |
| `saga_forge.saga.ended` | counter | `{saga}` | `saga_forge.saga.type`, `saga_forge.saga.outcome` |
| `saga_forge.event.handled` | counter | `{event}` | `saga_forge.saga.type`, `saga_forge.handling.outcome` |
| `saga_forge.retry.scheduled` | counter | `{retry}` | `saga_forge.saga.type`, `saga_forge.step` |
| `saga_forge.retry.exhausted` | counter | `{saga}` | `saga_forge.saga.type`, `saga_forge.step` |
| `saga_forge.message_store.messages` | gauge | `{message}` | `saga_forge.message.status` (`MessageStatus` names) |
| `saga_forge.message_store.oldest_unconsumed.age` | gauge | s | — |

The message-store gauges read `MessageStoreQueries.status()`, the source of the Actuator endpoint (FR-010), cached for
at most the configured polling interval so an export never runs more than one query per gauge set.

## Starter properties

| Property | Default | Meaning |
|---|---|---|
| `saga-forge.telemetry.enabled` | `true` | Whether the starter hands the application's `OpenTelemetry` bean to the runtime and transport. |
| `saga-forge.telemetry.tracing.enabled` | `true` | Whether spans are recorded. |
| `saga-forge.telemetry.metrics.enabled` | `true` | Whether metrics are recorded. |
| `saga-forge.telemetry.record-association-values` | `false` | Record association values on spans. |
| `saga-forge.telemetry.record-contents` | `false` | Record event and command contents on spans. |
| `saga-forge.telemetry.record-failure-messages` | `false` | Record failure messages and exception events on spans. |
