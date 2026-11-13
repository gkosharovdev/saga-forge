# Model reference

All annotations are in `io.github.gkosharovdev.sagaforge.core.annotation` (`saga-forge-core`). They are retained at
runtime and validated when the runtime starts; a violation stops startup with the message listed in
[Validation rules](validation-rules.md) (rule ids `R1` to `R23` below).

Other types a model uses:

| Type | Package (`io.github.gkosharovdev.sagaforge.` + ) | Role |
|---|---|---|
| `SagaContext` | `core.lifecycle` | a saga's named business values (`getState`, `putState`, `updateStateAttribute`) |
| `CommandGateway` | `core.command` | `send(Object command)`: how a step (or any code) dispatches a command |
| `CommandId` | `core.command` | the stable identity of a command, injected into a handler that declares it |
| `DomainEvents` | `core.event` | static `record(Object event)`: how handlers and aggregates record events |
| `EventPublisher` | `core.event` | `publish(Object event)`: how application code publishes events (a Spring bean) |
| `CompensationTrigger` | `core.compensation` | why compensation started; optional parameter of a compensation |
| `TransientFailure`, `FinalFailure` | `core.failure` | marker interfaces for exception classes: retry, or compensate without retry |
| `SagaStatus`, `HandlingOutcome` | `core.spi` | instance status and per-event outcome (used in tests and operations) |

## The saga class

### `@Saga`

- **Where**: on a concrete class (not an interface, not abstract). R1.
- **Needs**: exactly one `@SagaId` field, exactly one `@StartSaga` step, at least one `@EndSaga` step (R2, R3).
- **Construction**: a no-argument constructor (any visibility), or, with the starter, a prototype-scoped bean
  (`@Scope("prototype")`) to inject collaborators. A new saga object is created for every unit of work, so keep no
  state in plain fields: use `SagaContext`.
- **Attributes**: none.
- **Optional fields, recognized by declared type, no annotation**: at most one `SagaContext` field (R7) and at most one
  `CommandGateway` field (R11). The runtime sets them.
- **Never** declare a `@CommandHandler` on a saga (R10).

### `@SagaId`

- **Where**: on exactly one field of type `String` of the `@Saga` class (R2). The runtime sets it (`saga-1`-style ids in
  the test fixture, generated ids in production).
- **Attributes**: none.

### `@SagaStep`

- **Where**: on a method of the `@Saga` class that returns `void` (R8) and takes exactly one parameter whose type is
  annotated `@DomainEvent` (R5).
- **Rules**: no two steps of one saga take the same event type (R9). Steps run in event order, not declaration order.
  A step never returns a command; it calls `commandGateway.send(...)`.
- **Attributes**:

  | Attribute | Type | Default | Meaning |
  |---|---|---|---|
  | `associationProperty` | `String` | required | name of the field (record component) on the event whose value correlates the event to a saga instance, for example `"orderId"`. The event must have a non-null value for it at runtime, or delivery fails with `Event X has no value for association property 'p' of step s of saga S`. |
  | `retry` | `@RetryPolicy` | `@RetryPolicy` (one attempt, no retry) | how often the step's unit of work may run after a transient failure. |

- The association property need not be the event's `@AggregateIdentifier`: `PaymentProcessedEvent` is about a
  `Payment` (`paymentId`) but correlates by `orderId`.

### `@StartSaga`

- **Where**: on exactly one method per saga, together with `@SagaStep` (R3, R4). Its event creates a new instance.
- **Never** together with `@StartCompensation` (R20). A second start event with the same association value while an
  instance is active is reported `DUPLICATE_START` and changes nothing.

### `@EndSaga`

- **Where**: on one or more methods per saga, together with `@SagaStep` (R3, R4). When it runs, the unit of work
  commits and the instance is `COMPLETED`; the method's name is the recorded outcome (e.g. `completeOrder`).
- **Never** together with `@StartCompensation` (R20).

### `@StartCompensation`

- **Where**: on a step that handles a business-failure event (stock unavailable, delivery rejected), together with
  `@SagaStep`, never with `@StartSaga` or `@EndSaga` (R20).
- **Effect**: the step runs like any other; when its unit of work commits, the instance becomes `COMPENSATING` and the
  runtime runs the `@SagaCompensation` of every other completed step, most recently completed first, each in its own
  unit of work. The method's name is the failure outcome. Data the compensations need from the failure event must be
  written to `SagaContext` here: the event is not passed to them.
- **Attributes**: none.

### `@SagaCompensation`

- **Where**: on a method of the `@Saga` class that returns `void` and takes no parameter or one `CompensationTrigger`
  (R21); not itself a `@SagaStep` (R21).
- **Attributes**:

  | Attribute | Type | Default | Meaning |
  |---|---|---|---|
  | `forStep` | `String` | required | the **method name** of the `@SagaStep` it undoes. It must exist (R6), must be a `@SagaStep` (R6), must not be a `@StartCompensation` step (R22); at most one compensation per step (R22). |

- **Runs when**: a `@StartCompensation` step committed, or a step failed `FINAL` (exception implements `FinalFailure`)
  or `EXHAUSTED` (transient failure, attempts used up) after an earlier unit of work committed. Only steps that
  completed are compensated. A compensation that throws is never retried: the instance ends `REQUIRES_INTERVENTION`.
  A compensation can run again after a crash, so the commands it sends must be idempotent (pass `CommandId` on).

### `@RetryPolicy`

- **Where**: only as the value of `@SagaStep(retry = ...)`.
- **Attributes** (R19):

  | Attribute | Type | Default | Constraint |
  |---|---|---|---|
  | `maxAttempts` | `int` | `1` (no retry) | at least 1, attempts including the first |
  | `initialBackoffMillis` | `long` | `0` | not negative; wait before attempt 2 |
  | `multiplier` | `double` | `1.0` | finite, at least 1.0 (1.0 = fixed wait) |
  | `maxBackoffMillis` | `long` | `0` (= `initialBackoffMillis`) | 0 or at least `initialBackoffMillis`; cap on a single wait |
  | `retryOn` | `Class<? extends Throwable>[]` | `{}` | extra exception types treated as transient; no `Error` types |

- With `maxAttempts = 1`, every other attribute must keep its default.
- Wait before attempt `k >= 2`: `min(initialBackoffMillis * multiplier^(k-2), cap)`. The runtime waits in-process; the
  total wait of a policy may not exceed `saga-forge.runtime.max-total-retry-backoff` (default 30 s), checked at
  startup.
- Retried: a failure (or a cause in its chain) that implements `TransientFailure` or is an instance of a `retryOn` type,
  and implements no `FinalFailure`. Retrying re-runs the whole unit of work, not just the step.

## Commands and handlers

### `@Command`

- **Where**: on a command class (usually a record).
- **Rules**: handled by exactly one `@CommandHandler` across the model (R13); at most one `@AggregateIdentifier` field
  (R14), and exactly one when an `@Aggregate` instance method handles it (R15): that field selects the aggregate to
  load.
- **Attributes**:

  | Attribute | Type | Default | Meaning |
  |---|---|---|---|
  | `delivery` | `CommandDelivery` | `IN_UNIT_OF_WORK` | `IN_UNIT_OF_WORK`: the handler runs synchronously inside the sender's unit of work; its failure rolls the step back. `OUTBOX`: the command is stored with the sender's unit of work and handled after it commits, in a unit of work of its own; its failure does not roll the step back. An `OUTBOX` command must have a handler among the registered classes (R23) and needs an outbox that stores commands (the in-memory transport's does). |

### `@CommandHandler`

- **Where**: on a method or constructor that takes exactly one `@Command`-typed parameter (R12), in one of:
  - an `@Aggregate` **instance method**: the target aggregate is loaded first by the command's `@AggregateIdentifier`
    (through an `@AggregateLoader`, or with the starter the aggregate's Spring Data repository), then saved;
  - an `@Aggregate` **constructor or static factory**: creates a new aggregate;
  - a **service** class (with the starter: a Spring bean, `@Component`/`@Service`).
- **Never** on a `@Saga` (R10).
- **Extra parameters**: one optional `CommandId` (the command's stable identity; pass `commandId.value()` to outside
  systems as the idempotency key), and, with the starter, any bean the context can inject (`@Qualifier` honoured).
- **Attributes**: none. A handler records the resulting events with `DomainEvents.record(...)`.

## Aggregates and events

### `@Aggregate`

- **Where**: on an aggregate class. It extends and implements no Saga Forge type.
- **Needs**: exactly one `@AggregateIdentifier` field (R14).
- **Persistence**: with instance-method handlers, the runtime must be able to load it: an `@AggregateLoader` for it, or
  (starter) a Spring Data repository, or an `AggregateResolver` bean; otherwise startup fails.
- **Attributes**: none.

### `@AggregateIdentifier`

- **Where**: on a field (a record component works): exactly one on each `@Aggregate` and `@DomainEvent` type, at most
  one on each `@Command` type (R14). On a command it names the target aggregate. With the starter its type must convert
  to the repository's id type.

### `@DomainEvent`

- **Where**: on an event class (usually a record) used as a `@SagaStep` parameter or recorded by a handler.
- **Needs**: exactly one `@AggregateIdentifier` field (R14).
- **Attributes**:

  | Attribute | Type | Default | Meaning |
  |---|---|---|---|
  | `aggregate` | `Class<?>` | required | the `@Aggregate` type this event is about (R16). A marker aggregate class with just an identifier is fine when the state lives elsewhere (`Payment` in the examples). |

### `@AggregateLoader`

- **Where**: on a method of a host class (not the aggregate) that takes exactly one parameter, the identifier, and
  returns an `@Aggregate` type (R17); at most one per aggregate type (R18). Returning `null` fails the unit of work with
  `AggregateNotFoundException`. With the starter, a loader wins over the Spring Data repository.

### `@AggregateSaver`

- **Where**: on a method of a host class that takes exactly one `@Aggregate`-typed parameter (R17); optional, at most one
  per aggregate type (R18).

```java
public class OrderRepository {

    private final Map<String, Order> orders = new ConcurrentHashMap<>();

    @AggregateLoader
    public Order load(String orderId) {
        return orders.get(orderId);
    }

    @AggregateSaver
    public void save(Order order) {
        orders.put(order.orderId(), order);
    }
}
```

Source: `saga-forge-example/src/main/java/io/github/gkosharovdev/sagaforge/example/OrderRepository.java`.

## `SagaContext` values

`SagaContext` holds named values per saga instance. They are stored apart from the unit of work: a value written by a
step stays readable even when that step's unit of work rolled back, and a retried attempt sees what earlier attempts
wrote, so write values so that writing again is harmless.

- `putState(name, value)`: `null` is rejected; `getState(name, type)` returns `Optional.empty()` only when never
  written; reading with the wrong type throws `SagaStateTypeMismatchException`.
- On Postgres, values must be JSON scalars or collections of them: `String`, `Boolean`, `Integer`, `Long`, finite
  `Double`, `List`, `Map` with `String` keys, nested. **Store a `BigDecimal` as `toPlainString()`**, an `Instant` as
  its ISO string, an enum as its `name()`. A `Long` in the `int` range reads back as `Integer` on Postgres: read small
  numbers as `Integer` or store them as strings.
