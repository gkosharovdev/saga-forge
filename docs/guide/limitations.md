# Limitations to design around (0.4.0)

These are known limits of the current release, most important first for a Spring Boot monolith on Postgres and the
in-memory transport. Each has an id in the project's gap register (`.specify/memory/memory.md`), so you can follow its
status in later releases.

| # | Limitation | Design around it |
|---|---|---|
| 1 | **One delivering instance per database** (G-013-01, G-013-02). Several instances share the message store, but only the elected relay delivers: delivery throughput is that of one instance, and a message appended on a standby waits for the leader's next poll (up to `saga-forge.transport.poll-interval`). | Size the delivering instance for the whole message rate; keep handlers short. Spread load with the Kafka transport once it exists. |
| 2 | **An operator cannot retry a failed step** (G-015-01): a `FAILED` saga can only be compensated or marked resolved, since the runtime does not keep the event that triggered the step. Repairs act on one instance at a time (G-015-05) and run its compensations in the calling thread, so a long compensation holds the HTTP call (G-015-04). | Make steps whose failure you would want to retry mark it `TransientFailure` so `@RetryPolicy` retries it; keep compensations short; script bulk repairs over `SagaOperations.list`. |
| 3 | **Retry backoff is in-process** (G-005-01): capped (30 s total by default), and a waiting retry blocks the delivering thread and holds the instance (G-005-02). | Keep retry policies short; for a long wait use a deadline whose timeout step re-arms it, or a scheduled command (see [Deadlines](deadlines.md)). |
| 4 | **Deadlines and scheduled commands do not work with Spring Modulith interop** (G-017-01): the starter keeps Saga Forge's own log for a model with deadlines unless `saga-forge.transport.modulith-interop` is set. A deadline runs up to one poll interval late (G-017-04); overdue ones run in stored, not due, order after downtime (G-017-03). | Leave the interop property unset (or `false`) when a saga declares a deadline. Don't rely on sub-second deadline precision or on the order of two overdue deadlines. |
| 5 | **Two logs are not ordered against each other** (G-014-01): with Saga Forge's own log beside Modulith's registry, an aggregate's events in one log have no order against those in the other. Saga Forge's log orders its events per aggregate on every publishing route. | If you send an aggregate's events on to a broker, send them through one of the two, keyed by the aggregate id, and give consumers an aggregate version to drop stale events. |
| 6 | **An event published inside a host transaction waits for the relay's next poll** (G-014-05), up to `saga-forge.transport.poll-interval`, whether published through Spring or through `EventPublisher`. | Keep the poll interval short where saga start latency matters. |
| 7 | **An abandoned start is only reported by default** (`abandoned-start-policy=report`): a crash between creating an instance and its first unit of work leaves it `CREATED` until its start event is redelivered, which adopts it. The report is deduplicated per process, so each instance logs it again after a restart (G-015-07). | Alert on `saga.recovery.startAbandoned` or the `saga_forge.recovery{kind=start_reported}` counter; choose `fail` when the start event will not come again. |
| 8 | **Schema migrations need Flyway** (G-018-01): `initialize-schema=migrate` runs Saga Forge's versioned migrations with Flyway only; there are no Liquibase changesets. | With Liquibase or DBA-managed DDL, apply `schema.sql` and the upgrade scripts yourself and set `initialize-schema=validate` (see [Database schema](schema.md)). |
| 9 | **Saga metrics need an OpenTelemetry metric exporter** in Spring Boot (G-010-05). | Define an `SdkMeterProvider` bean (see [Operations](operations.md#opentelemetry)). |
| 10 | **A saga step cannot react to another saga's outcome event** (G-011-01): `SagaCompleted`, `SagaFailed` and `SagaCompensated` reach listeners, not sagas. | Listen in the application and publish a domain event, or send a command, that the follow-up saga starts on. |
| 11 | **A failed outbox command always compensates the whole saga**: no step can take the failure and name a business outcome (G-012-03), and a saga that already ended is not changed: a command that fails after its saga `COMPLETED` (G-012-02), or a compensation's command that fails after the saga `COMPENSATED` (G-012-01), only shows as a set-aside message and the warning `saga.command.failure.ignored`. | Send a command whose failure must stop the saga from a step the saga still waits after (it waits for the command's own reply event). Alert on `saga.command.failure.ignored` and on set-aside messages, and make compensation commands idempotent so redelivering one is safe. |

Further constraints of the model:

- **jMolecules domains** (see [Vocabularies](vocabularies.md)): an outbox command still needs Saga Forge's
  `@Command(delivery = OUTBOX)` beside jMolecules' `@Command` (G-016-01); a fixture test sees no Spring-published
  events, so a Spring Data aggregate's `@DomainEvents` need an event source registered in the test (G-016-02); a
  plain-typed identifier (`String`, `UUID`) on an event or command is never a link, mark it `@Association` (G-016-05);
  the in-memory transport keys an event linked only by identifier type by that type's name rather than the
  aggregate's, which still orders one aggregate's events together (G-016-06).

- **`SagaContext` values must be JSON scalars on Postgres** (`String`, `Boolean`, `Integer`, `Long`, finite `Double`,
  `List`, `Map` with `String` keys). Store `BigDecimal` as a string; a small `Long` reads back as `Integer` (G-003-07).
- **`SagaContext` values survive rollbacks and retries**: write them so that writing again is harmless.
- **Outside effects are not rolled back**: any handler that calls an outside system takes a `CommandId` parameter and
  passes `commandId.value()` on as the idempotency key (G-005-03). The triggering request itself is not deduplicated
  before a saga exists (G-005-04).
- **Compensation**: no wait for an undo's confirmation event (G-006-02); a stalled `COMPENSATING` instance resumes only
  through a recovery sweep after `saga-forge.recovery.stall-age`; a compensation sees the trigger's event type, not the event (G-006-06) — keep what it needs
  in `SagaContext`; a failed compensation is not retried on its own (G-006-09), only by an operator's resume, after which the instance keeps only the latest round's failure and earlier ones live in the history (G-015-03); compensations can run again after a crash, so
  their commands must be idempotent.
- **An unmarked exception ends the saga `FAILED` without compensation**; mark exceptions `TransientFailure` or
  `FinalFailure` (or list them in `retryOn`) when the difference matters.
- **An outbox command's failure is retried with the transport's backoff**, not the sending step's `@RetryPolicy`; an
  unmarked exception there is retried up to `saga-forge.transport.max-attempts` and then compensates the saga.
- **Publishing**: an application that publishes the same event both through Spring's `ApplicationEventPublisher` and
  through `EventPublisher` has it stored twice; keep one call. `saga-forge.application-events.mode=after-delivery` is
  deprecated and refused while the inbound route (`saga-forge.application-events.inbound.enabled`) is on. A
  hand-assembled runtime with the default `ImmediateDispatchOutbox` reports `DUPLICATE` for its own instance when a
  recorded event fans out to another saga type (G-014-04); so does any `EventReceiver` decorator that does not
  forward `receive(event, sourceSagaId)`.
- **Deadlines**: a deadline the relay already leased when it was cancelled is still delivered, then ignored
  (G-017-05); a scheduled command is not cancelled when its saga ends, so cancel it in the ending step if it must not
  run (G-017-06).
- **Operations**: every application instance runs recovery sweeps, each repair guarded by the instance's lock, not
  by leadership (G-015-02); an instance listing is read page by page, not as one snapshot, so an instance that changes
  between two pages may appear on both or neither (G-015-06).
- **Delivery order is per aggregate, not per saga instance** (G-007-04); the transport is at least once.
- **Not available**: Kafka transport, MySQL and MongoDB storage, a Quarkus extension, programmatic abort, Spring test
  slices for the fixture (G-009-01).
