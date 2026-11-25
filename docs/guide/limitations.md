# Limitations to design around (0.1.0)

These are known limits of the current release, most important first for a Spring Boot monolith on Postgres and the
in-memory transport. Each has an id in the project's gap register (`.specify/memory/memory.md`), so you can follow its
status in later releases.

| # | Limitation | Design around it |
|---|---|---|
| 1 | **One running application instance per database** (G-007-09). The in-memory transport's relay assumes it is the only consumer of its message store; a second instance is detected and logged, not coordinated. | Run one replica, or put sagas in a single-replica deployment. Blue/green deploys must stop the old instance before the new one delivers. |
| 2 | **No operator path out of `COMPENSATION_FAILED`** (G-006-03); the Actuator endpoint is read-only (G-008-02). | Make compensations simple and idempotent so they rarely fail; alert on `saga_forge.saga.ended{outcome=compensation_failed}`; repair by hand. |
| 3 | **Retry backoff is in-process** (G-005-01): capped (30 s total by default), and a waiting retry blocks the delivering thread and holds the instance (G-005-02). | Keep retry policies short; for a long wait use a deadline whose timeout step re-arms it, or a scheduled command (see [Deadlines](deadlines.md)). |
| 4 | **Deadlines and scheduled commands do not work with Spring Modulith interop** (G-017-01) and assume one running instance per message store (G-017-02). A deadline runs up to one poll interval late (G-017-04); overdue ones run in stored, not due, order after downtime (G-017-03). | Set `saga-forge.transport.modulith-interop=false` when a saga declares a deadline. Don't rely on sub-second deadline precision or on the order of two overdue deadlines. |
| 5 | **An event that continues one saga instance is not handed to another saga type** reacting to the same event class (G-ENG-24). | Give the second saga an event of its own (record a separate event in the handler). |
| 6 | **Events published through Spring's `ApplicationEventPublisher` do not reach sagas** (G-008-03). | Publish with Saga Forge's `EventPublisher` bean or `DomainEvents.record`. |
| 7 | **A crash between creating an instance and its first unit of work strands it `CREATED`**, and a redelivered start event is rejected as duplicate (G-ENG-05). | Monitor `saga_instance` rows that stay `CREATED`; re-drive by hand. |
| 8 | **No migration tool**; you apply `schema.sql` and upgrade scripts (G-003-25). | Add Saga Forge's DDL to your Flyway/Liquibase migrations. |
| 9 | **Saga metrics need an OpenTelemetry metric exporter** in Spring Boot (G-010-05). | Define an `SdkMeterProvider` bean (see [Operations](operations.md#opentelemetry)). |
| 10 | **A saga step cannot react to another saga's outcome event** (G-011-01): `SagaCompleted`, `SagaFailed` and `SagaCompensated` reach listeners, not sagas. | Listen in the application and publish a domain event, or send a command, that the follow-up saga starts on. |
| 11 | **A failed outbox command always compensates the whole saga**: no step can take the failure and name a business outcome (G-012-03), and a saga that already ended is not changed: a command that fails after its saga `COMPLETED` (G-012-02), or a compensation's command that fails after the saga `COMPENSATED` (G-012-01), only shows as a set-aside message and the warning `saga.command.failure.ignored`. | Send a command whose failure must stop the saga from a step the saga still waits after (it waits for the command's own reply event). Alert on `saga.command.failure.ignored` and on set-aside messages, and make compensation commands idempotent so redelivering one is safe. |

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
- **An outbox command's failure is retried with the transport's backoff**, not the sending step's `@RetryPolicy`; an
  unmarked exception there is retried up to `saga-forge.transport.max-attempts` and then compensates the saga.
- **Deadlines**: a deadline the relay already leased when it was cancelled is still delivered, then ignored
  (G-017-05); a scheduled command is not cancelled when its saga ends, so cancel it in the ending step if it must not
  run (G-017-06).
- **Delivery order is per aggregate, not per saga instance** (G-007-04); the transport is at least once.
- **Not available**: Kafka transport, MySQL and MongoDB storage, a Quarkus extension, programmatic abort, Spring test
  slices for the fixture (G-009-01).
