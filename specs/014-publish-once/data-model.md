# Data Model: Publish Once

No persisted data changes: no new table, column or `NewMessage`/`StoredMessage` field. The feature adds one SPI
method, one public accessor, and in-process state in the starter.

## Existing data this feature relies on

| Item | Where | Use |
|---|---|---|
| `NewMessage.sourceSagaId` / `StoredMessage.sourceSagaId` | core SPI; `saga_message_store.source_saga_id` | The instance whose unit of work recorded the event; passed to `EventReceiver.receive(event, sourceSagaId)` at delivery (R6). `null` for events published outside a unit of work. |
| `NewMessage.orderingKey` | core SPI | Unchanged: `@AggregateIdentifier` of the event, on every route (D10). |
| `StepRecord.status` | core `SagaInstance` | `COMPLETED` for the source instance's step means "ran in process"; never reverts, so a plain read decides. |

## New in-memory values

### Saga event types (`SagaRuntime.eventTypes()`)

- Unmodifiable `Set<Class<?>>`: every exact class some registered saga has a `@SagaStep` for (event-parameter steps
  only; `@DeadlineExpired` steps and outcome events excluded).
- Fixed when the runtime is built; the same before and after `start()`.

### `EventSubscriptions` (runtime, package-private)

- From the registered `SagaModel`s: event class → saga type names with a step for it.
- `otherSagaTypesHandle(Class<?> eventClass, String owningSagaType)`: `true` if a type other than `owningSagaType` has a
  step for `eventClass`.
- Immutable after construction; thread-safe.

### Recorded event (UnitOfWork's `Recording`), unchanged fields, changed rule

| Field | Meaning |
|---|---|
| `event` | the recorded event |
| `ranHere` | a step of the owning instance ran it in process |

Hand-on rule (`buffered()`):

| `ranHere` | another saga type has a step | handed to the outbox (`events`) |
|---|---|---|
| no | any | yes (unchanged) |
| yes | no | no (unchanged) |
| yes | yes | **yes** (new, Property 47) |

`recordedEvents` (every recorded event) is unchanged.

### Starter thread-bound state

| Name | Holds | Set by | Read by | Cleared |
|---|---|---|---|---|
| `PublishingGuard` | a flag: "Saga Forge is publishing to Spring on this thread" | `ApplicationEventPublishingOutbox.store`, `ApplicationEventPublishingEventPublisher` around `publishEvent` | `InboundSagaEventListener` | in `finally`, restoring the previous value (nesting) |
| `AlreadyPublishedEvents` | identity set of events Spring delivered and the inbound listener recorded into the current unit of work | `InboundSagaEventListener` | `ApplicationEventPublishingOutbox.store` (skips and removes) | after the Spring transaction completes (commit or rollback) |

## Configuration

| Property | Type | Default | Effect |
|---|---|---|---|
| `saga-forge.application-events.inbound.enabled` | boolean | `true` | registers `InboundSagaEventListener` |
| `saga-forge.application-events.mode` | `in-transaction` \| `after-delivery` (deprecated) \| `none` | `in-transaction` | unchanged; `in-transaction` also wraps `EventPublisher`; `after-delivery` with the route on refuses startup |
| `saga-forge.transport.modulith-interop` | boolean | not set → `true` unless the model declares a deadline | explicit `true` with deadlines still refuses startup |

## Lifecycle-history and telemetry

No new lifecycle kind. A source-instance skip produces no `HandlingResult` and no log. New log events:
`saga.transport.modulithInterop.skipped` (INFO, once at startup), `saga.applicationEvents.afterDeliveryDeprecated`
(WARN, once at startup).
