---
name: saga-forge
description: Write, change, test and debug Saga Forge sagas (io.github.gkosharovdev:saga-forge-*) in a Java/Spring Boot application. Use when code involves @Saga, @SagaStep, @StartSaga, @EndSaga, @StartCompensation, @SagaCompensation, @RetryPolicy, @CommandHandler, @Aggregate, @DomainEvent, @Command, CommandGateway, DomainEvents.record, EventPublisher, SagaContext, CommandId, SagaFixture, AggregateEventSource or a jMolecules-marked domain (saga-forge-jmolecules), or when a "Saga engine refused to start" error must be fixed.
---

# Saga Forge

Saga Forge 0.4.0: Java 21, Spring Boot 4.1, Postgres storage, in-memory transport (no broker). Annotations are in
`io.github.gkosharovdev.sagaforge.core.annotation`; `CommandGateway`/`CommandId` in `core.command`; `DomainEvents`/
`EventPublisher` in `core.event`; `SagaContext` in `core.lifecycle`; `CompensationTrigger` in `core.compensation`;
`TransientFailure`/`FinalFailure` in `core.failure`; `SagaOperations`/`OperatorRequest`/`RepairResult` in
`core.operations`; `SagaFixture` in `io.github.gkosharovdev.sagaforge.test`.

Guide: https://github.com/gkosharovdev/saga-forge/tree/master/docs/guide (single file:
https://github.com/gkosharovdev/saga-forge/blob/master/llms-full.txt). Javadoc:
https://javadoc.io/doc/io.github.gkosharovdev/saga-forge-core.

## Shape of a saga

```java
@Saga
public class OrderProcessingSaga {

    @SagaId
    private String sagaId;

    private SagaContext context;

    private CommandGateway commandGateway;

    @StartSaga
    @SagaStep(associationProperty = "orderId", retry = @RetryPolicy(maxAttempts = 3, initialBackoffMillis = 200,
            multiplier = 2.0, maxBackoffMillis = 1_000))
    public void handleOrderCreated(OrderCreatedEvent event) {
        context.putState("orderAmount", event.amount().toPlainString());
        commandGateway.send(new ProcessPaymentCommand(event.orderId(), event.amount()));
    }

    @SagaStep(associationProperty = "orderId")
    public void processPayment(PaymentProcessedEvent event) {
        context.putState("paymentId", event.paymentId());
        commandGateway.send(new ReserveStockCommand(event.orderId()));
    }

    @EndSaga
    @SagaStep(associationProperty = "orderId")
    public void completeOrder(OrderCompletedEvent event) {
    }

    @StartCompensation
    @SagaStep(associationProperty = "orderId")
    public void rejectOrder(StockUnavailableEvent event) {
    }

    @SagaCompensation(forStep = "processPayment")
    public void compensatePayment(CompensationTrigger trigger) {
        String paymentId = context.getState("paymentId", String.class).orElseThrow();
        commandGateway.send(new RefundPaymentCommand(sagaId, paymentId,
                new BigDecimal(context.getState("orderAmount", String.class).orElseThrow())));
    }
}
```

(Adapted from `saga-forge-example-spring/.../OrderProcessingSaga.java`.)

## Do

- Put `@Saga` on a concrete class with **exactly one** `@SagaId private String sagaId;`.
- Declare **at most one** `SagaContext` field and **at most one** `CommandGateway` field, recognized by type, no
  annotation; the runtime sets them. Keep no other state in saga fields: a new saga object is made per unit of work.
- Give every step `@SagaStep(associationProperty = "<event field name>")`, a `void` return, and exactly one parameter
  whose type is `@DomainEvent`. One step per event type per saga.
- Mark exactly one step `@StartSaga` and at least one `@EndSaga`; both always together with `@SagaStep`.
- Dispatch commands with `commandGateway.send(new XCommand(...))`.
- Make events records annotated `@DomainEvent(aggregate = X.class)` where `X` is `@Aggregate`, with exactly one
  `@AggregateIdentifier` field. The association property may be a different field.
- Make commands records annotated `@Command`. When an `@Aggregate` instance method handles it, give it exactly one
  `@AggregateIdentifier` field (the aggregate to load).
- Put `@CommandHandler` on an `@Aggregate` instance method (loaded by the command's identifier through its Spring Data
  repository or an `@AggregateLoader`, then saved), an `@Aggregate` constructor/static factory (creation), or a
  `@Service`/`@Component` method. Exactly one `@Command` parameter; one handler per command type.
- Record resulting events in handlers and aggregates with `DomainEvents.record(new XEvent(...))`.
- With `saga-forge-jmolecules` on the class path, a domain marked only with jMolecules needs none of the domain
  annotations above: `AggregateRoot<T, ID>`/`@AggregateRoot` (identifier `@Identity` or `getId()`), `DomainEvent`/
  `@DomainEvent`, jMolecules `@Command` and `@CommandHandler`. Events and commands link to their aggregate through an
  `Association<A, ID>`, `@Association` or `Identifier`-typed member; give typed identifiers a `toString()` that returns
  the plain value. The saga class keeps Saga Forge's annotations. An outbox command keeps Saga Forge's
  `@Command(delivery = OUTBOX)` beside jMolecules' `@Command`.
- When an aggregate holds its events (`domainEvents()`/`clearDomainEvents()`), register an `AggregateEventSource` bean
  (`supports`, `takeEvents`: return and clear them); the runtime records them after the handler, before the save. In
  `SagaFixture` pass `.domainTypes(DomainTypes.of(new JMoleculesDomainTypeResolver()))` and
  `.aggregateEventSource(...)`.
- Start a saga from application code by publishing its start event the usual Spring way
  (`ApplicationEventPublisher.publishEvent(event)`, or a Spring Data aggregate's `@DomainEvents`), inside your
  `@Transactional` method if you also change state. Saga Forge's `EventPublisher.publish(event)` works too (and also
  publishes to Spring); use one of the two per event, never both.
- Store `SagaContext` values as JSON scalars: `String`, `Boolean`, `Integer`, `Long`, finite `Double`, `List`,
  `Map<String, ?>`. **`BigDecimal` → `amount.toPlainString()`**, read back with `new BigDecimal(...)`; `Instant` →
  ISO string; enum → `name()`.
- Give every handler that calls an outside system a `CommandId commandId` parameter and pass `commandId.value()` as the
  idempotency key. Compensation commands too: compensations can run again after a crash.
- Model business failures as events (`StockUnavailableEvent`) handled by a `@StartCompensation` `@SagaStep`; write
  what compensations need into `SagaContext` in the steps.
- Undo a step with `@SagaCompensation(forStep = "<exact step method name>")` returning `void`, taking nothing or a
  `CompensationTrigger`.
- Retry with `@SagaStep(retry = @RetryPolicy(...))` and mark retryable exceptions `implements TransientFailure` (or list
  them in `retryOn`); mark "do not retry, compensate" exceptions `implements FinalFailure`. Keep total backoff under
  30 s. A `FinalFailure` compensates only if an earlier unit of work of the instance committed; in the start step, or
  anywhere in a chain of steps that runs in one unit of work, the saga ends `FAILED` with nothing to undo.
- React to how a saga ended by listening for its outcome event (`core.outcome`): `SagaCompleted`, `SagaFailed`
  (`phase()` `STEP` or `COMPENSATION`) or `SagaCompensated`, with `@TransactionalEventListener` or
  `@ApplicationModuleListener`. Deduplicate on `eventId()` (`sagaId:STATUS`); delivery is at least once.
- Use `@Command(delivery = CommandDelivery.OUTBOX)` when the handler must run after the sending step commits and its
  failure must not roll the step back. If it fails for good (a `FinalFailure` at once, anything else after the
  transport's `max-attempts`), the saga that sent it compensates every completed step, the sender included; the
  trigger's kind is `COMMAND_FAILED` and it names the command type. The transport retries it with its own backoff,
  never the step's `@RetryPolicy`. In `SagaFixture`, `outboxCommandAttempts(n)` (default 1) plays `max-attempts`.
- Give a wait a time limit with `@StartDeadline(name = "...", duration = "PT30M" | "${key:PT30M}" | durationFrom =
  "<SagaContext attribute>", cancelledBy = {...})` on a `@SagaStep`, and one `@StartCompensation @DeadlineExpired("...")
  @SagaStep` (no `associationProperty`; no parameter or one `DeadlineReached`) as the timeout step. Leaving `RUNNING`
  closes every open deadline, so `cancelledBy` is only needed for steps the saga keeps running after. The trigger's kind
  is `DEADLINE_EXPIRED`. Test with `fixture.given(...).whenTimeElapses(Duration.ofMinutes(30))
  .expectDeadlineExpired("...")`.
- Schedule a command for later with `commandGateway.schedule(command, instant)` (an `OUTBOX` command); keep the
  returned `ScheduledCommandId`'s `value()` in `SagaContext` and `cancel` it if it must not run: ending the saga does
  not cancel it.
- With the starter, a saga that needs collaborators is a `@Scope("prototype")` bean; handler hosts are beans.
- Run as many application instances as needed over one database: one elected relay delivers, the others stand by.
  Tune takeover with `saga-forge.transport.leadership.lease-duration` / `renew-interval` / `check-interval` (30 s / 10 s
  / 5 s); keep handlers idempotent (`CommandId`), since a takeover can redeliver.
- Repair one saga instance through `SagaRuntime.operations()` (`SagaOperations`), each call with an
  `OperatorRequest(operator, reason)`: `resumeCompensation` (`COMPENSATION_FAILED` or a stalled `COMPENSATING`; failed
  compensations run again, so they must be idempotent), `compensate` (`CREATED`, `RUNNING`, `FAILED`; trigger kind
  `OPERATOR_REQUEST`) and `markResolved` (`FAILED`, `COMPENSATION_FAILED`; the status stays). Each returns
  `RepairResult.Applied` or `NotApplied` with a reason. `list(SagaInstanceQuery)` pages instances, `history(sagaId)`
  reads the lifecycle history. In a test: `fixture.whenOperator(ops -> ops.resumeCompensation(...))
  .instance(...).expectHistory(...)`, `expectResolved()`.
- With the starter, recovery sweeps run every minute on every instance (`saga-forge.recovery.*`): a `COMPENSATING`
  saga untouched for `stall-age` (5 m) resumes, a `CREATED` saga with no completed step after `abandonment-age` (15 m)
  is reported (`abandoned-start-policy=report`, the default) or ended `FAILED` (`fail`). A redelivered start event
  adopts a stranded `CREATED` instance. Over HTTP: the `sagas` Actuator endpoint (`GET /actuator/sagas`,
  `GET /actuator/sagas/{id}`, `POST /actuator/sagas/{id}/{resume-compensation|compensate|resolve}` with
  `{"reason": "..."}`); the POST needs `management.endpoint.sagas.access=unrestricted` and an authenticated principal,
  so secure it with Spring Security.
- Test with `SagaFixture.of(Saga.class).build().given(...).when(...).expectCommands(...)`; use `.handlers(...)` to run
  real handlers and `givenAggregates(...)` to seed aggregates.

## Don't

- Don't return a command (or anything) from a step; don't make a step take a command.
- Don't put `@CommandHandler` on a `@Saga` class.
- Don't make aggregates extend or implement a Saga Forge type, and don't call repositories for them from a saga.
- Don't publish the same event both through Spring's `ApplicationEventPublisher` and through `EventPublisher`: it
  is stored twice. Don't use `saga-forge.application-events.mode=after-delivery` (deprecated; refused while
  `saga-forge.application-events.inbound.enabled` is on).
- Don't use a plain `@EventListener` for saga events in another module: it runs inside the saga's transaction. Use
  `@ApplicationModuleListener` or `@TransactionalEventListener`.
- Don't store `BigDecimal`, `Instant`, `LocalDate`, records, POJOs or `null` in `SagaContext`.
- Don't put `@StartCompensation` with `@StartSaga` or `@EndSaga`; don't map a `@SagaCompensation` to a
  `@StartCompensation` step; at most one compensation per step.
- Don't declare two steps for the same event type, two `@StartSaga` steps, or two handlers for one command.
- Don't rely on long retry waits (in-process, 30 s total by default); give a wait a time limit with a deadline instead.
- Don't set `saga-forge.transport.modulith-interop=true` with deadlines or `CommandGateway.schedule` (startup fails;
  left unset, the starter keeps Saga Forge's own log for a model with deadlines), or
  with the default `ImmediateDispatchOutbox`. Don't put `associationProperty` on a `@DeadlineExpired` step, start a
  deadline on an `@EndSaga`/`@StartCompensation` step, or make a timeout step `@StartSaga`.
- Don't add `SagaRuntime` configuration classes in a Spring Boot app: the starter assembles everything.
- Don't use classes in packages named `internal`.
- Don't mark one class with two roles (an aggregate that is also an event), or point Saga Forge's and jMolecules'
  identifier markers at different members (R30). Don't link a jMolecules event by a plain `String`/`UUID` member:
  it is never a link; use `@Association` or a typed identifier.
- Don't expect every instance to deliver messages or run deadlines: only the active relay does, and delivery throughput
  is that of one instance. Don't look for `saga-forge.transport.heartbeat-interval`: it was replaced by the
  leadership properties.
- Don't write a saga step that takes `SagaCompleted`, `SagaFailed` or `SagaCompensated`: they are not domain events and
  reach no saga. Start follow-up work from a listener.
- Don't expect an operator to retry a `FAILED` saga's failed step: there is no retry yet; compensate it or mark it
  resolved. Don't allow the `sagas` endpoint's write access without Spring Security, and don't turn on
  `saga-forge.operations.expose-context-values` when context values hold personal data.
- Don't look for `REQUIRES_INTERVENTION`: a failed compensation ends `COMPENSATION_FAILED` (existing Postgres
  databases get it from schema version V2, `migrate` applies it).
- Don't copy Saga Forge's `schema.sql` or its migrations into the application's own Flyway location, and don't set
  `initialize-schema=always` in production. With Flyway, set `saga-forge.storage.postgres.initialize-schema=migrate`
  and add `flyway-core` and `flyway-database-postgresql`: Saga Forge applies its versioned migrations under its own
  history table `saga_forge_schema_history`, after the application's, and takes over a database created from
  `schema.sql`. With Liquibase or DBA-managed DDL, set `initialize-schema=validate`.

## When startup fails

The message is `Saga engine refused to start (N errors):` followed by one error per line. Look each line up in
https://github.com/gkosharovdev/saga-forge/blob/master/docs/guide/validation-rules.md. Most common:

| Error starts with | Fix |
|---|---|
| `@SagaStep method m in S must return void` | return `void`, `commandGateway.send(...)` |
| `... parameter type T must be annotated with @DomainEvent` | annotate the event `@DomainEvent(aggregate = X.class)` |
| `T must declare exactly one @AggregateIdentifier field` | one `@AggregateIdentifier` on each aggregate and event |
| `@Command type C is handled by @Aggregate instance method ... must declare an @AggregateIdentifier field` | add `@AggregateIdentifier` to the command |
| `@Aggregate A has instance-method @CommandHandler methods but no @AggregateLoader and no AggregateResolver` | add a Spring Data repository or `@AggregateLoader` |
| `@CommandHandler H.m parameter #n T cannot be resolved` | make `T` a bean, or inject it into the constructor |
| `T (jMolecules ...) concerns no known aggregate` | scan the aggregate's package, or name it on the link (`@Association(aggregateType = A.class)`) |
| `T is marked ... and ...; a type has one role` | give the class one role |
| `Command C is delivered through the outbox but has no @CommandHandler` | add the handler (in a scanned package) |
| `@SagaCompensation on m: forStep 'x' does not reference an existing method` | use the exact step method name |
| `total retry backoff n ms exceeds the engine maximum` | shorten the `@RetryPolicy` |
| `Event E has no value for association property 'p'` (at runtime) | the event needs a non-null field named `p` |

Schema failures are reported by Spring Boot's failure analysis, not in that list:

| Description starts with | Fix |
|---|---|
| `Saga Forge's tables [...] do not exist in the database.` | `initialize-schema=migrate` (Flyway), or `always` in development |
| `Saga Forge's schema does not match V<N> (...)`, one line per difference | `initialize-schema=migrate`, or repair the listed tables, columns and constraints |
| `Saga Forge's tables exist but match no released schema version. Closest: V<N>` | repair the listed differences, or set `saga-forge.storage.postgres.migration.baseline-version` |
| `...initialize-schema=migrate needs Flyway, but ... is not on the class path.` | add `org.flywaydb:flyway-core` and `org.flywaydb:flyway-database-postgresql` |
