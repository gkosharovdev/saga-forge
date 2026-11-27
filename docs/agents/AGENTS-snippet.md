# AGENTS.md snippet for applications using Saga Forge

Paste the section below into your repository's `AGENTS.md` (or `CLAUDE.md`). It is deliberately short; the full rules
are in the [agent skill](saga-forge/SKILL.md) and the [guide](../guide/README.md).

```markdown
## Saga Forge (sagas)

This application uses Saga Forge 0.5.0 (`io.github.gkosharovdev:saga-forge-*`) with the Spring Boot starter, Postgres
storage and the in-memory transport. Guide: https://github.com/gkosharovdev/saga-forge/blob/master/llms-full.txt;
startup error → fix: https://github.com/gkosharovdev/saga-forge/blob/master/docs/guide/validation-rules.md.

- A saga is a concrete `@Saga` class with exactly one `@SagaId String` field, at most one `SagaContext` field and at
  most one `CommandGateway` field (no annotation on those two).
- Every step is `@SagaStep(associationProperty = "<event field>")`, returns `void`, takes exactly one `@DomainEvent`
  parameter; one step per event type. Exactly one `@StartSaga`, at least one `@EndSaga`, both with `@SagaStep`.
- Steps dispatch commands with `commandGateway.send(...)`; they never return commands.
- `@CommandHandler`s live on `@Aggregate`s or service beans, never on a saga; one handler per `@Command` type. They
  record events with `DomainEvents.record(...)`. Aggregates extend/implement no Saga Forge type.
- Events: `@DomainEvent(aggregate = X.class)` with exactly one `@AggregateIdentifier`. Commands to an aggregate
  instance method carry one `@AggregateIdentifier`.
- Application code publishes events once, the usual Spring way (`ApplicationEventPublisher` or Spring Data
  `@DomainEvents`); sagas receive those whose type they have a step for. Saga Forge's `EventPublisher` also works,
  but never publish one event both ways. Other modules listen with `@ApplicationModuleListener`, not a plain
  `@EventListener`.
- `SagaContext` values are JSON scalars: store `BigDecimal` as `toPlainString()`, `Instant` as a string.
- Handlers that call outside systems take a `CommandId` and pass `commandId.value()` as the idempotency key.
- Business failures are events handled by a `@StartCompensation` step; undo steps with
  `@SagaCompensation(forStep = "<step method name>")`. A `FinalFailure` compensates only after an earlier unit of work
  of the instance committed; otherwise the saga ends `FAILED`.
- An `@Command(delivery = OUTBOX)` command that fails for good (`FinalFailure`, or the transport's `max-attempts` used
  up) makes the saga that sent it compensate (trigger kind `COMMAND_FAILED`); its step is not rolled back.
- A saga that ends records `SagaCompleted`, `SagaFailed` or `SagaCompensated` (`core.outcome`); listen with
  `@TransactionalEventListener` or `@ApplicationModuleListener`, deduplicate on `eventId()`. No saga step can take one.
  A failed compensation ends `COMPENSATION_FAILED`.
- Time limits are deadlines: `@StartDeadline(name, duration | durationFrom, cancelledBy)` on a step and one
  `@StartCompensation @DeadlineExpired(name) @SagaStep` timeout step (no `associationProperty`). They need the in-memory
  transport's outbox and Saga Forge's own log (leave `saga-forge.transport.modulith-interop` unset or `false`). Test
  with `whenTimeElapses(...)`.
- Several application instances may share the database: one elected relay delivers messages and deadlines, the others
  stand by (`saga-forge.transport.leadership.*`). Don't expect delivery on every node; a takeover can redeliver.
- Operators repair one saga at a time through `SagaRuntime.operations()` or the `sagas` Actuator endpoint:
  resume a failed compensation, compensate, or mark `FAILED`/`COMPENSATION_FAILED` resolved (no retry of a failed
  step). Compensations may run again on a resume, so they pass `CommandId` on. Recovery sweeps
  (`saga-forge.recovery.*`) resume stalled compensations and report abandoned starts.
- With `saga-forge-jmolecules`, jMolecules markers (`AggregateRoot`, `DomainEvent`, `@Command`, `@CommandHandler`)
  replace the domain annotations; links are `Association`/`@Association`/`Identifier` members, never plain `String`s.
  An aggregate that holds its events gets an `AggregateEventSource` bean that returns and clears them.
- Saga Forge's tables come from its own versioned migrations: `saga-forge.storage.postgres.initialize-schema=migrate`
  with `flyway-core` and `flyway-database-postgresql` (own history table `saga_forge_schema_history`), or `validate`
  for a schema managed elsewhere. Never copy its `schema.sql` or migrations into the application's migrations.
- Correlation ids travel as process metadata: `saga-forge.metadata.mdc-keys=correlationId` captures the MDC key when
  a saga starts and puts it back in the MDC while its steps, handlers and compensations run. Read it in code through a
  `SagaMetadata` saga field or handler parameter (at most one each). No secrets or personal data in it.
- Test sagas with `SagaFixture` (`saga-forge-test`), no Spring context needed.
```
