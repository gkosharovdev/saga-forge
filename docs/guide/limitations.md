# Limitations to design around (0.1.0)

These are known limits of the current release, most important first for a Spring Boot monolith on Postgres and the
in-memory transport. Each has an id in the project's gap register (`.specify/memory/memory.md`), so you can follow its
status in later releases.

| # | Limitation | Design around it |
|---|---|---|
| 1 | **One running application instance per database** (G-007-09). The in-memory transport's relay assumes it is the only consumer of its message store; a second instance is detected and logged, not coordinated. | Run one replica, or put sagas in a single-replica deployment. Blue/green deploys must stop the old instance before the new one delivers. |
| 2 | **No operator path out of `REQUIRES_INTERVENTION`** (G-006-03); the Actuator endpoint is read-only (G-008-02). | Make compensations simple and idempotent so they rarely fail; alert on `saga_forge.saga.ended{outcome=requires_intervention}`; repair by hand. |
| 3 | **No deadlines or scheduled waits** (G-ENG-25, G-005-01). A saga waits for its next event forever; retry backoff is in-process and capped (30 s total by default), and a waiting retry blocks the delivering thread and holds the instance (G-005-02). | Run a scheduled job that finds stale instances and publishes a timeout event the saga has a step for. Keep retry policies short. |
| 4 | **An event that continues one saga instance is not handed to another saga type** reacting to the same event class (G-ENG-24). | Give the second saga an event of its own (record a separate event in the handler). |
| 5 | **Events published through Spring's `ApplicationEventPublisher` do not reach sagas** (G-008-03). | Publish with Saga Forge's `EventPublisher` bean or `DomainEvents.record`. |
| 6 | **A crash between creating an instance and its first unit of work strands it `CREATED`**, and a redelivered start event is rejected as duplicate (G-ENG-05). | Monitor `saga_instance` rows that stay `CREATED`; re-drive by hand. |
| 7 | **No migration tool**; you apply `schema.sql` and upgrade scripts (G-003-25). | Add Saga Forge's DDL to your Flyway/Liquibase migrations. |
| 8 | **Saga metrics need an OpenTelemetry metric exporter** in Spring Boot (G-010-05). | Define an `SdkMeterProvider` bean (see [Operations](operations.md#opentelemetry)). |

Further constraints of the model:

- **`SagaContext` values must be JSON scalars on Postgres** (`String`, `Boolean`, `Integer`, `Long`, finite `Double`,
  `List`, `Map` with `String` keys). Store `BigDecimal` as a string; a small `Long` reads back as `Integer` (G-003-07).
- **`SagaContext` values survive rollbacks and retries**: write them so that writing again is harmless.
- **Outside effects are not rolled back**: any handler that calls an outside system takes a `CommandId` parameter and
  passes `commandId.value()` on as the idempotency key (G-005-03). The triggering request itself is not deduplicated
  before a saga exists (G-005-04).
- **Compensation**: no wait for an undo's confirmation event (G-006-02); a `COMPENSATING` instance with no further event
  never resumes (G-006-05); a compensation sees the trigger's event type, not the event (G-006-06) — keep what it needs
  in `SagaContext`; a failed compensation is never retried (G-006-09); compensations can run again after a crash, so
  their commands must be idempotent.
- **An unmarked exception ends the saga `FAILED` without compensation**; mark exceptions `TransientFailure` or
  `FinalFailure` (or list them in `retryOn`) when the difference matters.
- **Delivery order is per aggregate, not per saga instance** (G-007-04); the transport is at least once.
- **Not available**: Kafka transport, MySQL and MongoDB storage, a Quarkus extension, programmatic abort, Spring test
  slices for the fixture (G-009-01).
