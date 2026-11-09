# Contract: `CommandGateway` and command handlers

**Package**: `io.github.gkosharovdev.sagaforge.core.command` | **Kind**: interface, no implementation shipped
in core | **Consumers**: `@SagaStep` methods (through the saga's `CommandGateway` field), services, and
application code (through dependency injection).

## `void send(Object command)`

- **Pre-conditions**: `command` is non-null and its class is annotated `@Command`.
- **Effect**: finds the single `@CommandHandler` method whose `@Command`-typed parameter matches the command's
  class, obtains its target, and invokes it synchronously inside the caller's current saga-scoped unit of
  work. `send` returns only after the handler returned and the aggregate was saved (see
  [event-publisher.md](./event-publisher.md) for events).
  - Service-hosted handler: target obtained from `HandlerInstanceProvider.instanceOf(handlerClass)`.
  - Creation handler (constructor or static factory of an `@Aggregate`): invoked directly; the new aggregate is
    then saved (see "Saving" below).
  - Instance-method handler of an `@Aggregate`: the target id is the command's `@AggregateIdentifier` value.
    The aggregate is loaded by the client's `@AggregateLoader` method for that type if one exists, otherwise by
    the host's `AggregateResolver`. After the handler returns it is saved.
  - Saving: the client's `@AggregateSaver` method for that type if one exists, otherwise
    `AggregateResolver.save`, otherwise nothing (the client's own unit of work persists the change, e.g. JPA
    dirty checking). Client code never implements a Saga Forge interface (spec FR-013).
  - Events recorded during the handler through `DomainEvents.record` go to the unit of work's recorder.
- **Failure**:
  - `IllegalArgumentException` for a `null` or non-`@Command` argument.
  - `NoCommandHandlerException` (unchecked, exposes the command type) if no handler exists. The unit of work
    is rolled back (spec FR-011).
  - `AggregateNotFoundException` (unchecked, exposes aggregate type and id) if the loader or resolver finds no
    aggregate.
- Any exception thrown by the handler, a loader or saver, the resolver, or the provider propagates unchanged.
    The unit of work is marked for rollback before the exception leaves `send`, so a step that catches it
    cannot prevent the rollback (spec FR-012).

## `@CommandHandler` method rules (checked by `validateAll`)

1. Exactly one parameter whose type is `@Command`-annotated, on a method or constructor; other parameters are
   allowed (FR-009). The engine supplies only the command; other parameters are resolved by the host (the
   Spring starter will resolve beans), and the harness supports only the single-parameter form.
2. Declared on an `@Aggregate` class (instance method, or constructor / static factory for creation) or on any
   other non-`@Saga` class (FR-010).
3. At most one handler per command type across all validated classes (FR-011).
4. The return value, if any, is ignored.
5. A command handled by an instance-method handler of an `@Aggregate` declares exactly one
   `@AggregateIdentifier` (FR-014).
6. An `@Aggregate` with instance-method handlers needs a loader: a client `@AggregateLoader` or a resolver that
   supports it. Checked when the engine starts, because host resolvers are not visible to the scanner.

## Outside a unit of work

`send` called with no active unit of work (for example from a request handler) opens a unit of work for the
handler's own changes. It is not saga-scoped: events raised in it follow the "outside any saga unit of work"
rule and are dispatched through the transport after it commits.

## 004 delta (2026-11-09, `specs/004-saga-engine`)

Source: `specs/004-saga-engine/contracts/contract-deltas.md`. Where this contract says "the future engine", read `saga-forge-engine`.

- Rule 1, "the harness supports only the single-parameter form": the engine supplies the `@Command` argument and
  resolves every other parameter through `HandlerParameterResolver`s, checked at startup
  (`specs/004-saga-engine/contracts/host-plugins.md`).
- Rule 6, "checked when the engine starts": implemented as Property 21 (`specs/004-saga-engine/contracts/engine-startup.md`).
- Several `AggregateResolver`s may be registered: the first that `supports` the type is used for both load and save.
- "Outside a unit of work": the non-saga unit of work runs in one `LocalTransaction`; its recorded events go to the
  handoff with `sagaType`/`sagaId` `null` (after commit, or before it with `IN_TRANSACTION` timing); a failure rolls
  back and propagates from `send` unchanged.
- `send` on a thread with no engine unit of work, including a thread a handler started, is "outside a unit of work".
- Loader and saver host instances come from `HandlerInstanceProvider`, on each use.
