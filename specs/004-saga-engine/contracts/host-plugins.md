# Contract: host plug-ins `HandlerParameterResolver` and `SagaObjectProvider`

**Package**: `io.github.gkosharovdev.sagaforge.core.spi` | **Kind**: interfaces, implemented by host integrations
only (the Spring starter: bean lookup and prototype beans), never by client code | **Consumer**: the engine. Spec:
FR-009, FR-019, FR-035; research §9.

All null arguments raise `IllegalArgumentException`. Several resolvers may be registered; the first that supports a
parameter wins. At most one `SagaObjectProvider`.

## `HandlerParameterResolver`

| Operation | When | Contract |
|---|---|---|
| `boolean supports(Executable handler, Parameter parameter)` | at `start()`, for every `@CommandHandler` method or constructor parameter that is not its `@Command` parameter | Side-effect free; `true` if `resolve` can supply the parameter |
| `Object resolve(Executable handler, Parameter parameter)` | inside the unit of work, on its thread, before each invocation of that handler | Returns a value assignable to the parameter type (`null` only for a non-primitive parameter); a thrown exception fails the unit of work (FR-011) |

The engine always supplies the `@Command` argument itself (FR-019). With no resolver registered, every handler with
another parameter is a startup error.

## `SagaObjectProvider`

| Operation | When | Contract |
|---|---|---|
| `boolean supports(Class<?> sagaType)` | at `start()`, for every registered `@Saga` class | Side-effect free |
| `Object newInstance(Class<?> sagaType)` | at the start of every unit of work of that saga type | Returns a new object of exactly `sagaType`, never one returned before; a thrown exception fails the unit of work |

After `newInstance` (or, without a provider, the accessible no-argument constructor) the engine sets the `@SagaId`
field, the `SagaContext`-typed field and the `CommandGateway`-typed field, overwriting any value the provider set.
Other fields are the provider's business; they are dependencies, never read or persisted by the engine, and
`SagaInstance.state` stays empty (G-ENG-06).

## Existing plug-ins, as the engine uses them

| Plug-in | Use |
|---|---|
| `HandlerInstanceProvider` | Instance for service-hosted `@CommandHandler` methods and for classes hosting `@AggregateLoader`/`@AggregateSaver` methods; looked up on first use, not at startup; a failure fails the unit of work |
| `AggregateResolver` (several, ordered) | Load: client `@AggregateLoader`, else the first resolver that `supports` the type. Save: client `@AggregateSaver`, else the first supporting resolver, else nothing. `supports` is also called at `start()` (Property 21) |
