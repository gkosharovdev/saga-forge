# Contract: Starter Configuration

## `saga-forge.application-events.inbound.enabled`

- Boolean, default `true`, in `SagaForgeProperties.ApplicationEvents.Inbound`; documented in the configuration
  metadata and the starter README table (`ReadmePropertiesTest`).
- `false`: no `InboundSagaEventListener`; behaviour as before 014 for Spring-published events.

## `saga-forge.application-events.mode=after-delivery` (deprecated)

- AD1: Javadoc `@deprecated` on `ApplicationEventsMode.AFTER_DELIVERY`; README marks it deprecated; startup logs
  `saga.applicationEvents.afterDeliveryDeprecated` (WARN) once.
- AD2: with `inbound.enabled=true`, startup fails with `SagaForgeConfigurationException`:
  "saga-forge.application-events.mode=after-delivery re-publishes delivered events, which the inbound route
  (saga-forge.application-events.inbound.enabled=true) already received from Spring." Action: "Use
  saga-forge.application-events.mode=in-transaction, or set saga-forge.application-events.inbound.enabled=false."
- AD3: with `inbound.enabled=false`, behaviour as before 014 apart from AD1.

## Interop default with deadlines

- DI1: `saga-forge.transport.modulith-interop` not set in the `Environment`, the registry present, and some collected saga
  class declares `@StartDeadline` → the `MessageStore` bean is Saga Forge's own `PostgresMessageStore`; INFO
  `saga.transport.modulithInterop.skipped reason=deadlines sagas=<types>`.
- DI2: explicitly `true` with deadlines → startup refused as today; `DeadlinesRequireOwnLogFailureAnalyzer` explains it.
- DI3: not set, no deadline → `ModulithMessageStore` as today.
- DI4: the `sagaforge` Actuator endpoint and health report the store actually used.

## Tests

`InboundRouteDisabledTest`, `AfterDeliveryConflictTest` (AD2 failure analysis text), `DeadlineInteropDefaultTest`
(DI1 to DI3 with Modulith's JDBC registry), `ReadmePropertiesTest`.
