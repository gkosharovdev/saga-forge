# AGENTS.md snippet for applications using Saga Forge

Paste the section below into your repository's `AGENTS.md` (or `CLAUDE.md`). It is deliberately short; the full rules
are in the [agent skill](saga-forge/SKILL.md) and the [guide](../guide/README.md).

```markdown
## Saga Forge (sagas)

This application uses Saga Forge 0.1.0 (`io.github.gkosharovdev:saga-forge-*`) with the Spring Boot starter, Postgres
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
- Application code publishes start events with Saga Forge's `EventPublisher`, never Spring's
  `ApplicationEventPublisher`. Other modules listen with `@ApplicationModuleListener`, not a plain `@EventListener`.
- `SagaContext` values are JSON scalars: store `BigDecimal` as `toPlainString()`, `Instant` as a string.
- Handlers that call outside systems take a `CommandId` and pass `commandId.value()` as the idempotency key.
- Business failures are events handled by a `@StartCompensation` step; undo steps with
  `@SagaCompensation(forStep = "<step method name>")`.
- Test sagas with `SagaFixture` (`saga-forge-test`), no Spring context needed.
```
