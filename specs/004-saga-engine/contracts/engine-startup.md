# Contract: `SagaEngine` assembly and startup

**Package**: `io.github.gkosharovdev.sagaforge.engine` (`saga-forge-engine`) | **Kind**: public class with a
builder, plus `StartupReport` and `SagaEngineStartupException` | **Consumers**: host integrations (the Spring starter
next; tests here). Application developers using the starter never build one (README principle 5). Spec: FR-019,
FR-034 to FR-036; research §9.

## Builder

```java
SagaEngine engine = SagaEngine.builder()
        .register(OrderProcessingSaga.class, Order.class, OrderRepository.class, PaymentService.class)
        .storage(storageProvider)                 // required
        .contextStore(sagaContextStore)           // required
        .transactions(transactionBoundary)        // required
        .dispatcher(eventDispatcher)              // required
        .handlerInstances(handlerInstanceProvider)// required if a service handler, loader or saver exists
        .aggregateResolver(resolver)              // repeatable, ordered
        .parameterResolver(parameterResolver)     // repeatable, ordered
        .sagaObjects(sagaObjectProvider)          // optional
        .handoff(eventHandoff)                    // optional; default DispatchingEventHandoff
        .clock(clock).sagaIds(supplier).unitOfWorkIds(supplier)   // optional
        .logger(systemLogger)                     // optional
        .build();                                 // IllegalArgumentException/IllegalStateException for missing required inputs
engine.start();                                   // SagaEngineStartupException if the model is not runnable
```

`build()` checks only that required inputs are present and non-null; model problems are reported by `start()`, all
at once. After `start()`: `engine.receive(event)` (`EventReceiver`), `engine.commandGateway()`,
`engine.eventPublisher()`, `engine.isStarted()`.

## `void start()`

1. Runs `ReflectiveAnnotationScanner.validateAll(registered classes)`; event and command types reached from
   signatures are validated without being listed (002 contracts/validation.md).
2. Runs the engine checks below over the registered classes and plug-ins, collecting every error.
3. If anything failed: the engine becomes `REFUSED`, logs every error, and throws
   `SagaEngineStartupException(StartupReport report)`. It accepts no event, ever (US6-5).
4. Otherwise calls `EventDispatcher.registerSagaType(sagaClass)` for every `@Saga` class in registration order; a
   failure is reported like a check failure (step 3). Then the engine is `STARTED`.

`start()` twice throws `IllegalStateException`.

## Engine checks and messages

Placeholders as in 002 contracts/validation.md (`{class}` simple class name, `{method}`, `{type}`); `{param}` is the
parameter's position and type, e.g. `#2 Clock`.

| Check | Message |
|---|---|
| `@Aggregate` with an instance-method `@CommandHandler` and neither a registered `@AggregateLoader` nor a supporting `AggregateResolver` (Property 21) | `@Aggregate {class} has instance-method @CommandHandler methods but no @AggregateLoader and no AggregateResolver supporting it` |
| Handler parameter other than the `@Command` one that no `HandlerParameterResolver` supports (Property 22) | `@CommandHandler {class}.{method} parameter {param} cannot be resolved: no HandlerParameterResolver supports it` |
| `@Saga` class the `SagaObjectProvider` does not support (Property 23) | `@Saga class {class} is not supported by the registered SagaObjectProvider` |
| `@Saga` class without a no-argument constructor, no provider registered (Property 23) | `@Saga class {class} must declare a no-argument constructor, or a SagaObjectProvider supporting it must be registered` |
| `registerSagaType` threw | `EventDispatcher rejected @Saga class {class}: {exception message}` |

## `StartupReport`

`StartupReport(ValidationReport validation, List<String> engineErrors)`: unmodifiable copies, no `null`s;
`isValid()` iff `validation.isValid()` and `engineErrors` is empty; `allErrors()` lists `validation.allErrors()` then
`engineErrors`. `SagaEngineStartupException` (unchecked) exposes `report()`; its message lists `allErrors()`, one per
line.

## Not checked at startup

Handler, loader and saver host instances (from `HandlerInstanceProvider`, on first use); whether a resolver can
actually find a given aggregate; anything about the stores' contents.
