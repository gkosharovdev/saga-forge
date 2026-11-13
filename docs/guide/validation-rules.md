# Validation rules: error → fix

The runtime validates the whole model when it starts (`SagaRuntime.start()`; with the starter, while the application
context starts; with `SagaFixture`, in `build()`). It collects **every** error in one pass and refuses to start with
`SagaRuntimeStartupException`:

```text
Saga engine refused to start (2 errors):
@SagaStep method processPayment in OrderProcessingSaga must return void; dispatch commands through the CommandGateway instead
Command ChargeCard is delivered through the outbox but has no @CommandHandler
```

With the starter this appears under Spring Boot's `APPLICATION FAILED TO START`. Find each line below by its fixed text;
`{X}` marks a part filled in with a class, method or number. Messages come from
`ReflectiveAnnotationScanner` (`saga-forge-core`, rules R1 to R23), `StartupChecks` (`saga-forge-runtime`) and the
starter's configuration checks.

## Saga class rules

| Rule | Error text | What it means | How to fix |
|---|---|---|---|
| R1 | `@Saga class {S} must be a concrete class, not an interface or abstract class` | `@Saga` is on an interface or abstract class. | Put `@Saga` on a concrete class. |
| R2 | `@Saga class {S} must declare exactly one @SagaId field, found {n}` | No `@SagaId` field, or more than one. | Declare exactly one `@SagaId private String sagaId;`. |
| R2 | `@SagaId field {f} in {S} must be of type String` | The id field is a `UUID`, `long`, ... | Make it `String`. |
| R3 | `@Saga class {S} must declare exactly one @StartSaga method, found {n}` | Zero or several start steps. | Mark exactly one step `@StartSaga`; split into two sagas if two events can start a process. |
| R3 | `@Saga class {S} must declare at least one @EndSaga method, found 0` | No terminal step. | Mark the step(s) reacting to the final success event(s) `@EndSaga`. |
| R4 | `Method {m} in {S} is annotated with @StartSaga but missing @SagaStep` | `@StartSaga` alone. | Add `@SagaStep(associationProperty = "...")` to the same method. |
| R4 | `Method {m} in {S} is annotated with @EndSaga but missing @SagaStep` | `@EndSaga` alone. | Add `@SagaStep(associationProperty = "...")` to the same method. |
| R5 | `@SagaStep method {m} in {S} must accept exactly one parameter, found {n}` | A step takes no parameter or several. | Take exactly one event parameter; get other data from `SagaContext` or injected collaborators. |
| R5 | `@SagaStep method {m} parameter type {T} must be annotated with @DomainEvent` | The step takes a command, a DTO or an unannotated class. | Annotate the event type `@DomainEvent(aggregate = X.class)` with one `@AggregateIdentifier`. Steps react to events, never to commands. |
| R6 | `@SagaCompensation on {m}: forStep '{name}' does not reference an existing method in {S}` | Typo, or the step was renamed. | Set `forStep` to the exact **method name** of the step it undoes. |
| R6 | `@SagaCompensation on {m}: forStep '{name}' must reference a @SagaStep method` | `forStep` names a helper method. | Point it at a `@SagaStep` method. |
| R7 | `@Saga class {S} must declare at most one SagaContext field, found {n}` | Several `SagaContext` fields. | Keep one field; store several values under different names. |
| R8 | `@SagaStep method {m} in {S} must return void; dispatch commands through the CommandGateway instead` | The step returns a command or a value. | Return `void` and call `commandGateway.send(command)`. |
| R9 | `@Saga class {S} has more than one @SagaStep reacting to {Event}: {m1}, {m2}` | Two steps take the same event type. | Keep one step per event type and branch inside it, or introduce distinct event types. |
| R10 | `@Saga class {S} must not declare @CommandHandler method {m}` | A handler is on the saga. | Move the handler to an `@Aggregate` or a service class. |
| R11 | `@Saga class {S} must declare at most one CommandGateway field, found {n}` | Several gateway fields. | Keep one `private CommandGateway commandGateway;`. |
| R19 | `@SagaStep method {m} in {S} has an invalid retry policy: {violations}` | See the violations below; several are joined in one line. | Fix the `@RetryPolicy` attributes. |
| R20 | `Method {m} in {S} is annotated with @StartCompensation but missing @SagaStep` | `@StartCompensation` alone. | Add `@SagaStep(associationProperty = "...")`. |
| R20 | `@StartCompensation method {m} in {S} cannot also be annotated with @StartSaga` | The start step is also the failure step. | Use a separate step for the failure event. |
| R20 | `@StartCompensation method {m} in {S} cannot also be annotated with @EndSaga` | One step is both outcomes. | Success is `@EndSaga`, business failure is `@StartCompensation`: separate steps on separate events. |
| R21 | `@SagaCompensation method {m} in {S} must return void` | Compensation returns a value. | Return `void`; send commands through the gateway. |
| R21 | `@SagaCompensation method {m} in {S} must declare no parameter or one CompensationTrigger parameter` | Compensation takes the event or other parameters. | Take nothing or `CompensationTrigger trigger`; read the data from `SagaContext` (write it in the step). |
| R21 | `@SagaCompensation method {m} in {S} cannot also be annotated with @SagaStep` | A step is also a compensation. | Keep them separate methods. |
| R22 | `@SagaCompensation methods {m1}, {m2} in {S} both compensate step {step}` | Two compensations for one step. | Merge them into one method. |
| R22 | `@SagaCompensation method {m} in {S} compensates step {step}, which is a @StartCompensation step` | The failure step itself has a compensation. | Remove it: the failure step starts compensation and is never undone. |

R19 violations (after `has an invalid retry policy: `):

| Violation text | Fix |
|---|---|
| `maxAttempts must be at least 1, found {n}` | `maxAttempts >= 1`. |
| `initialBackoffMillis must not be negative, found {n}` | Use `0` or more. |
| `multiplier must be a finite number of at least 1.0, found {x}` | Use `1.0` (fixed) or more. |
| `maxBackoffMillis must be 0 or at least initialBackoffMillis {i}, found {n}` | Raise the cap, or use `0`. |
| `maxBackoffMillis must not be negative, found {n}` | Use `0` or more. |
| `retryOn must not name Error type {T}` | List `Exception` types only. |
| `initialBackoffMillis must keep its default when maxAttempts is 1` (also `multiplier`, `maxBackoffMillis`, `retryOn`) | Raise `maxAttempts`, or drop the other attributes. |

## Handler, aggregate, event and command rules (whole model)

| Rule | Error text | What it means | How to fix |
|---|---|---|---|
| R12 | `@CommandHandler method {m} in {H} must declare exactly one @Command parameter, found {n}` | The handler takes no `@Command`, or two. For a constructor `{m}` is the class name. | Take exactly one `@Command` parameter (plus optional `CommandId` and injected beans). Annotate the command class `@Command`. |
| R13 | `Command {C} has more than one @CommandHandler: {H1.m}, {H2.m}` | Two handlers for one command type. | Keep one handler per command type. |
| R14 | `{T} must declare exactly one @AggregateIdentifier field, found {n}` | An `@Aggregate` or `@DomainEvent` without, or with several, identifiers. | Annotate exactly one field (or record component) `@AggregateIdentifier`. |
| R14 | `@Command type {C} must declare at most one @AggregateIdentifier field, found {n}` | A command with several identifiers. | Keep at most one. |
| R15 | `@Command type {C} is handled by @Aggregate instance method {A.m} and must declare an @AggregateIdentifier field` | The runtime cannot know which aggregate to load. | Add `@AggregateIdentifier` to the command's id field, or make the handler a constructor/static factory (creation) or move it to a service. |
| R16 | `@DomainEvent type {E} names aggregate {X}, which is not annotated @Aggregate` | `aggregate = X.class` points at a non-aggregate. | Annotate `X` with `@Aggregate` (a marker class with one `@AggregateIdentifier` field is fine), or name the right class. |
| R17 | `@AggregateLoader method {m} in {H} must take exactly one parameter and return an @Aggregate type` | Wrong loader signature. | `@AggregateLoader Order load(String orderId)`. |
| R17 | `@AggregateSaver method {m} in {H} must take exactly one @Aggregate-typed parameter` | Wrong saver signature. | `@AggregateSaver void save(Order order)`. |
| R18 | `Aggregate {A} has more than one @AggregateLoader: {H1.m}, {H2.m}` (or `@AggregateSaver`) | Two loaders (or savers) for one aggregate. | Keep one of each per aggregate type. |
| R23 | `Command {C} is delivered through the outbox but has no @CommandHandler` | An `@Command(delivery = OUTBOX)` type no registered class handles. | Add a handler, make sure its class is in the scanned packages (or a `SagaModelContributor`), or use `IN_UNIT_OF_WORK`. In `SagaFixture` record mode, `register(HandlerClass.class)`. |

## Runtime startup checks

| Error text | What it means | How to fix |
|---|---|---|
| `@Aggregate {A} has instance-method @CommandHandler methods but no @AggregateLoader and no AggregateResolver supporting it` | Nothing can load the aggregate. | With the starter: give it a Spring Data repository (`CrudRepository<A, Id>`) or an `@AggregateLoader`. Plain Java: register a loader host or an `AggregateResolver`. |
| `@CommandHandler {H.m} parameter #{n} {T} cannot be resolved: no HandlerParameterResolver supports it` | An extra handler parameter is neither the command, a `CommandId`, nor resolvable. | With the starter: make `{T}` a bean (or inject it into the handler's constructor instead). Plain Java: register a `HandlerParameterResolver`. |
| `@CommandHandler {H.m} declares more than one CommandId parameter: #{a}, #{b}` | Two `CommandId` parameters. | Keep one. |
| `Command {C} handled by @CommandHandler {H.m} is delivered through the outbox, but the Outbox does not support commands` | `OUTBOX` command without a command-capable outbox. | Use the in-memory transport (the starter's default), or switch the command to `IN_UNIT_OF_WORK`. |
| `@SagaStep {S.m}: total retry backoff {n} ms exceeds the engine maximum {max} ms` | The retry policy waits longer in total than allowed (default 30 000 ms). | Shorten the policy, or raise `saga-forge.runtime.max-total-retry-backoff` (plain Java: `SagaRuntime.Builder.maxTotalRetryBackoff`). Long waits are not supported (see [Limitations](limitations.md)). |
| `@Saga class {S} must declare a no-argument constructor, or a SagaObjectProvider supporting it must be registered` | The saga cannot be instantiated. | Add a no-argument constructor (any visibility), or with the starter declare it a `@Scope("prototype")` bean. |
| `@Saga class {S} is not supported by the registered SagaObjectProvider` | A custom provider refuses the saga. | Extend your provider, or drop it. |
| `EventDispatcher rejected @Saga class {S}: {reason}` | The transport refused the saga type. | Follow `{reason}`. |

## Spring Boot starter failures

Reported by the starter's failure analyzers with a *Description* and an *Action*:

| Description | Action |
|---|---|
| `No Saga Forge storage module is on the class path; a saga needs durable storage.` | Add `saga-forge-storage-postgres`, or define your own `StorageProvider` and `SagaContextStore` beans. |
| `The [postgres] storage module is on the class path, but no storage was assembled: it needs a DataSource and a PlatformTransactionManager bean.` | Configure `spring.datasource.*` with `spring-boot-starter-jdbc`. |
| `Several Saga Forge storage modules are on the class path: ...` | Set `saga-forge.storage.type`, or remove the others. |
| `saga-forge.storage.type is {x}, but {artifact} is not on the class path ...` | Add the artifact or change the property. |
| `Saga Forge's tables [...] do not exist in the database.` | Apply `schema.sql` (Flyway/Liquibase), or set `saga-forge.storage.postgres.initialize-schema=always` in development. |
| `Saga Forge's schema is incomplete: tables [...] are missing while others exist.` | Apply the matching `schema-upgrade-NNN.sql` scripts. |
| `No TransactionBoundary is available.` | Configure a `DataSource` and a `PlatformTransactionManager`. |
| `No Saga Forge transport was assembled.` | Keep `saga-forge-transport-inmemory` (comes with the starter) with a configured storage module. |
| `Several SagaRuntime beans are defined: ...` | Keep one runtime per application. |
| `Saga {S} is defined as bean '{b}' with a scope other than prototype; ...` | Annotate the saga class `@Scope("prototype")`, or do not declare it as a bean. |
| `Saga {S} has several beans: ...` | At most one prototype bean per saga class. |
| `{H} declares a @CommandHandler, @AggregateLoader or @AggregateSaver method but is not a bean.` | Annotate the host `@Component`/`@Service` (aggregates are exempt: they are loaded, not injected). |
| `{H} hosts handlers but has several beans: ...` | One bean per handler host class. |
| `Aggregate {A} has an @AggregateIdentifier of type {X}, but its Spring Data repository uses id type {Y} and no conversion exists.` | Use the same type, or register a converter. |
| `Saga model class {C} found in package {p} cannot be loaded: ...` | Fix the class's dependencies, or narrow `saga-forge.scan-packages`. |
| `saga-forge.scan-packages contains '{x}', which is not a Java package name.` | List package names like `com.example.orders`. |
| `saga-forge.runtime.max-total-retry-backoff must be a positive duration, but is {x}.` | Use a positive duration such as `30s`. |

## Errors while running

| Error text | What it means | How to fix |
|---|---|---|
| `Event {E} has no value for association property '{p}' of step {m} of saga {S}` | The event lacks the field named by `associationProperty`, or it is `null`. | Add the field to the event (same name), or fix `associationProperty`. |
| `{path} has unsupported value type {T}; ...` (Postgres) / `stored SagaContext value '{name}' of type {T}, which durable storage cannot persist` (fixture) | A `SagaContext` value is not a JSON scalar (often `BigDecimal`, `Instant`, a record). | Store `amount.toPlainString()`, `instant.toString()`, `enum.name()`; or a `Map`/`List` of scalars. |
| `AggregateNotFoundException` | The loader or repository found no aggregate for the command's identifier. | Create the aggregate before sending the command, or use a creating handler (constructor/static factory). |
| `NoCommandHandlerException` | A command type has no registered handler. | Add the handler and make sure its class is scanned. |
| Outcome `DUPLICATE_START` | A start event arrived while an instance with the same association value is active. | Expected for redelivery; otherwise use distinct association values. |
| Outcome `UNMATCHED` | A non-start event found no active instance. | The instance already ended, or the start event never arrived. |
