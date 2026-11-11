# Contract: Starter configuration

All auto-configurations are `@ConditionalOnProperty(prefix = "saga-forge", name = "enabled", matchIfMissing = true)`.

| Auto-configuration | Condition | Beans (each `@ConditionalOnMissingBean`) |
|---|---|---|
| `SagaRuntimeConfiguration` | `SagaRuntime` class present | `SagaModelCollector`, `SagaRuntime` (built, not started; storage detection and the Spring host checks run while it is built), `CommandGateway`, `EventPublisher`, `SagaRuntimeLifecycle` |
| `SpringHostConfiguration` | always with the runtime | `SpringSagaObjectProvider`, `SpringHandlerInstanceProvider`, `SpringHandlerParameterResolver` |
| `SpringDataAggregateConfiguration` | `org.springframework.data.repository.support.Repositories` present | `SpringDataAggregateResolver` |
| `PostgresStorageConfiguration` | `PostgresStorageProvider` present, `DataSource` bean, storage type unset or `postgres` | `PostgresStorageProvider`, `PostgresSagaContextStore`, `SchemaInitializer` |
| `InMemoryTransportConfiguration` (007) | `InMemoryTransport` present, no broker transport | as in 007 |
| `ApplicationEventsConfiguration` | `application-events.mode` | `IN_TRANSACTION`: a `BeanPostProcessor` wraps the `Outbox` bean (when its timing is `IN_TRANSACTION`) in `ApplicationEventPublishingOutbox`; `AFTER_DELIVERY`: 007's `ApplicationEventBridge`; `NONE`: neither |
| `ModulithInteropConfiguration` (007) | as in 007 | as in 007 |
| `SagaForgeActuatorConfiguration` | actuator classes present and an `InMemoryTransport` bean; the health indicator also needs `spring-boot-health` | `SagaForgeEndpoint`, `SagaForgeHealthIndicator` |

**Ordering**: `SagaRuntimeLifecycle` phase = `TransportLifecycle.PHASE - 1`: the runtime starts first and the transport
stops first. A refresh failure stops whatever started.

**Overrides (FR-014)**: an application bean of type `SagaRuntime`, `StorageProvider`, `SagaContextStore`,
`InMemoryTransport`, `TransactionBoundary`, `SagaObjectProvider`, `HandlerInstanceProvider`,
`HandlerParameterResolver` or `AggregateResolver` replaces the starter's. With an application `SagaRuntime`, the
lifecycles still start it (if not started) and the transport around it. Two `SagaRuntime` beans fail startup.

**Failures**: `SagaForgeConfigurationException` (detection, beans, properties, schema) and `SagaRuntimeStartupException`
(model) are explained by `FailureAnalyzer`s registered in `META-INF/spring.factories`, each with a description naming
the offending class, bean, property or module and an action.
