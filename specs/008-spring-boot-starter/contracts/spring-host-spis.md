# Contract: Spring implementations of the host SPIs

| SPI | Implementation | Behaviour |
|---|---|---|
| `SagaObjectProvider` | `SpringSagaObjectProvider` | `supports(type)`: a bean of that type exists, or the class has a no-argument constructor. `newInstance(type)`: `getBean(type)` when there is a bean, else the no-argument constructor (the runtime's own path). Startup fails if a saga bean is not prototype-scoped (message names the bean and suggests `@Scope("prototype")`). |
| `HandlerInstanceProvider` | `SpringHandlerInstanceProvider` | `instanceOf(type)`: the single bean of that type. Checked for every registered handler/loader/saver host at startup: none or several fail startup naming the class. |
| `HandlerParameterResolver` | `SpringHandlerParameterResolver` | Resolves via `resolveDependency(DependencyDescriptor(MethodParameter, required=true))`, honouring `@Qualifier`, `@Lazy`, `Optional`, `ObjectProvider`, collections. `supports` resolves once and caches; none-or-many is "unsupported", so the runtime's startup report lists the parameter. `CommandId` and the command stay the runtime's. |
| `AggregateResolver` | `SpringDataAggregateResolver` | `supports(type)`: `Repositories.hasRepositoryFor(type)`. `load(type, id)`: `invokeFindById(id)` with the application's `ConversionService`. `save(aggregate)`: `invokeSave`. Client `@AggregateLoader`/`@AggregateSaver` keep precedence. Startup fails if the identifier type cannot be converted to the repository's id type. |

All run in the unit of work's Spring transaction through `SpringTransactionBoundary` (007).
