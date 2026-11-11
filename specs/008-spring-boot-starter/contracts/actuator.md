# Contract: Actuator endpoint and health (FR-020)

- `SagaForgeEndpoint`: `@Endpoint(id = "sagaforge")`, one `@ReadOperation` returning the payload in data-model.md. No
  write or delete operation.
- `SagaForgeHealthIndicator` (contributor name `sagaforge`): `UP` when `SET_ASIDE` count is 0; otherwise `DOWN` with
  detail `setAside`.
- Registered only when `spring-boot-actuator` and `spring-boot-health` are present; switched by Spring Boot's standard
  `management.endpoint.sagaforge.*`, `management.endpoints.web.exposure.*` and `management.health.sagaforge.enabled`.
- Absent actuator classes: nothing registered, nothing fails.
