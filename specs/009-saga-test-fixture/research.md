# Research: Saga Test Fixture

Decisions behind [plan.md](plan.md). Each section: Decision, Rationale, Alternatives considered.

## §1 Run the shipped runtime, not a model of it

**Decision**: the fixture builds a real `SagaRuntime` (`SagaRuntime.builder()`) over in-memory plug-ins it owns, and
every given and published event goes through `SagaRuntime.receive`. The fixture adds no execution logic of its own:
it records, controls time and identities, delivers what the runtime hands off, and checks expectations.

**Rationale**: FR-002 (same semantics as production) and SC-003 (zero parity differences). Routing, the unit of work,
duplicates, retries and compensation are already implemented and tested in the runtime; any re-implementation would
drift. The builder already exposes what a fixture needs: `clock`, `sagaIds`, `unitOfWorkIds`, `retryPause`,
`maxTotalRetryBackoff`, `sagaObjects`, `handlerInstances`, `aggregateResolver`, `parameterResolver`, `outbox`.

**Alternatives considered**: reuse 002's `SagaTestHarness` (rejected: test-only, not the shipped runtime, G-ENG-13);
an Axon-style fixture that invokes saga methods by reflection (rejected: would re-implement correlation, lifecycle
and compensation ordering and drift from the runtime).

## §2 Recording commands and injecting failures: a command interceptor in the runtime

**Decision**: add one additive, host-facing extension point to core and the runtime:

- `core.spi.CommandInterceptor` — `void intercept(CommandInvocation invocation)`; called for every command the runtime
  is about to handle (a command sent from a step or compensation, a command sent outside a unit of work, and an
  outbox command delivered through `CommandReceiver`), on the handling thread, inside the unit of work.
- `core.spi.CommandInvocation` — `command()`, `commandId()`, `hasHandler()`, `proceed()` (runs the handler route;
  throws `NoCommandHandlerException` when there is none, as today).
- `SagaRuntime.Builder.commandInterceptor(CommandInterceptor)` — at most one; the default proceeds.

An exception thrown by the interceptor is treated exactly like an exception of the handler: the transaction is marked
rollback-only, the failure is classified by the runtime's rules, and retries and compensation follow. A command the
interceptor completes without calling `proceed()` counts as handled.

The fixture's interceptor (a) applies the test's command failure rules (FR-008), (b) records the command with its
`CommandId`, and (c) proceeds only if `hasHandler()` and the handler is registered (FR-011, FR-012); otherwise it returns,
leaving the command recorded for the test to answer.

**Rationale**: today a command with no route throws `NoCommandHandlerException` and fails the step, so record-and-answer
(the clarified default) is impossible without a hook. Generating stub handlers is not possible either: the commands a
step sends are only known at run time. The same hook serves failure injection, and later observability (G-008-02's
metrics and tracing need a per-command hook too). It is additive and JDK-only (Constitution Principle I).

**Alternatives considered**: a fixture-only fake `CommandGateway` injected into the saga (rejected: bypasses the
runtime's command identity, duplicate skipping and in-unit-of-work handling, breaking FR-002); wrapping
`HandlerInstanceProvider` to throw (rejected: cannot see commands without a handler, nor aggregate handlers);
failure injection by test doubles only (rejected: the developer would need a double per handler and could not fail
commands in record mode).

## §3 Where the dispatched commands are read from

**Decision**: the interceptor records each command with its `CommandId` into a buffer owned by the fixture's
`TransactionBoundary`; the buffer is kept on commit and dropped on rollback. A command's step is
`commandId.stepName()` (a step or compensation method name), so the fixture can report commands per step without
runtime changes. Outbox commands are additionally captured at send time from `OutgoingMessages.commands` and reported
as "sent for outbox delivery".

**Rationale**: a retried unit of work sends its commands again with the same identities (005); reporting the commands of
the committed attempt (with the attempt count on the step record) matches what a reader of the saga expects, while
"all attempts" stays inspectable for idempotency tests (edge case "Identities").

**Alternatives considered**: reading `StepRecord.handledCommands` from the stored instance (rejected: holds identities,
not command content, and only for handled commands).

## §4 Delivery: a synchronous loop-back transport

**Decision**: the fixture's `EventDispatcher` and `IN_TRANSACTION` `Outbox` queue what the runtime hands off; after the
publishing call returns, the fixture drains the queue in order — events to `runtime.receive`, outbox commands to
`runtime.commandReceiver()` — until it is empty. All of it happens inside `given(...)`/`when(...)` on the test thread.

**Rationale**: deterministic and fast (SC-002, SC-005); covers events that travel between saga types and outbox
commands (User Story 4 scenario 4) without threads. Transport behaviour beyond "delivered after commit, in order"
(leases, redelivery after a crash, per-aggregate ordering under concurrency) is explicitly out of scope (spec
Assumptions).

**Alternatives considered**: the real in-memory transport with an `InMemoryMessageStore` (rejected: asynchronous, needs
waiting in tests, and pulls `saga-forge-transport-inmemory` into the fixture's dependencies).

## §5 Time, retry waits and identities

**Decision**: a fixture-owned mutable clock (`FixtureClock`, starting at a fixed instant, settable and advanceable by
the test) is passed to the runtime; the `RetryPause` advances that clock by the requested wait instead of sleeping.
Saga ids are `saga-1`, `saga-2`, … and unit-of-work ids `uow-1`, … per fixture; `CommandId`s follow from them
(`CommandId.of(sagaId, step, type, n)`).

**Rationale**: FR-009, FR-010, SC-005. All three hooks already exist on the builder.

**Alternatives considered**: random ids with matchers that ignore them (rejected: tests could not refer to an
identity, which idempotency tests need).

## §6 In-memory plug-ins: the fixture owns production-quality copies

**Decision**: the fixture module contains its own in-memory `TransactionBoundary` (with commit/rollback callbacks for
§3), `StorageProvider` (copy-on-write instances, `findByIdAndLock`), `SagaContextStore` (with the value check of §8) and
`AggregateResolver` (§7) as main code. The runtime's test-jar doubles stay where they are for the runtime's own tests.

**Rationale**: the fixture is published (FR-001); test-jar code is neither published nor supported. Maven forbids the
runtime's tests depending on a module that depends on the runtime, so the runtime cannot switch to the fixture's
doubles. The duplication is small and recorded (G-009-02).

**Alternatives considered**: publish the runtime's test-jar (rejected: exposes test utilities with no API promise); a
third `saga-forge-runtime-testkit` module (rejected for now: one more artifact for four classes; revisit if Quarkus or
another host needs them).

## §7 Aggregates in handler mode

**Decision**: when the test registers handlers, the fixture registers an `AggregateResolver` over an in-memory store
keyed by aggregate type and identifier value (read from the `@AggregateIdentifier` field). The test seeds it with
`givenAggregates(...)` and reads it with `aggregate(type, id)`. Because the runtime gives a client
`@AggregateLoader`/`@AggregateSaver` precedence over resolvers, registered loader/saver hosts keep working unchanged
(FR-012). In record mode no aggregate is touched.

**Rationale**: matches how the starter backs aggregates with Spring Data (same SPI), so a saga tested here behaves as it
does under the starter.

**Alternatives considered**: requiring a client loader/saver in tests (rejected: Spring Data users have none).

## §8 `SagaContext` value check (FR-019)

**Decision**: the fixture's `SagaContextStore.save` checks every value against the value types every supported
durable store accepts — today Postgres's rule: `String`, `Boolean`, `Integer`, `Long`, finite `Double`, `Map` with
`String` keys and `List`, nested — and throws `UnsupportedContextValueException` naming saga type, saga id, key path and
value type. The runtime calls the store from `putState`/`updateStateAttribute` in the step, so the step fails; the
fixture recognises the exception among the unit of work's failures and turns the `given`/`when` call into a failed
assertion that names the step, rather than reporting a `FAILED` saga. `checkContextValues(false)` turns it off.

A contract test in `saga-forge-storage-postgres` (test scope on the fixture) checks that Postgres accepts every value
the fixture accepts and rejects every value it rejects, so the two rules cannot drift.

**Rationale**: catches G-003-07 at unit-test time (SC-007). The rule is duplicated rather than imported because the
fixture must not depend on a storage module (FR-001); the contract test makes the duplication safe.

**Alternatives considered**: a round trip through Jackson in the fixture (rejected: adds a dependency and still differs
from the store's own checks).

## §9 Model validation

**Decision**: the fixture calls `runtime.start()` when it is built; a `SagaRuntimeStartupException` propagates
unchanged, carrying the full `StartupReport` (FR-003, User Story 6 scenario 3).

## §10 API shape

**Decision**: a fluent given/when/then API in package `io.github.gkosharovdev.sagaforge.test`:

```java
SagaFixture fixture = SagaFixture.of(OrderProcessingSaga.class).build();
fixture.given(new OrderCreatedEvent("o-1", amount))
       .when(new PaymentProcessedEvent("payment-1", "o-1", amount))
       .expectCommands(new ReserveStockCommand("o-1"))
       .expectContext("paymentId", "payment-1")
       .expectCompletedStep("processPayment")
       .expectStatus(SagaStatus.RUNNING);
```

`when(...)` returns a `Then` object of assertions over the event under test; `result()` exposes the underlying
`EventResult` for AssertJ users. Assertions address the single instance the event touched, or a named one with
`.instance(OrderProcessingSaga.class, "o-1")`. Failures throw `SagaExpectationError extends AssertionError`
(FR-014, FR-015). Full surface: [contracts/fixture-api.md](contracts/fixture-api.md).

**Rationale**: the given/when/then vocabulary is what adopters of Axon and Spring Modulith `Scenario` know; no JUnit
extension is needed (FR-015); the fixture is a plain object, so parallel tests share nothing (FR-016).

**Alternatives considered**: a JUnit 5 extension with parameter injection (rejected for now: FR-015; can be layered on
later); expectations as AssertJ custom assertions (rejected: forces AssertJ on adopters).

## §11 Parity suite

**Decision**: `FixtureParityTest` runs each scenario of User Stories 1 to 3 twice — through the fixture and through a
plain `SagaRuntime` assembled over the runtime's test-jar doubles with the same clock, ids and failure rules — and
compares the handling outcomes, stored instances (status, steps, attempts, failure, compensation) and dispatched
commands (SC-003, FR-017).

## §12 Module and build

**Decision**: new module `saga-forge-test` (artifact `saga-forge-test`), depending on `saga-forge-core` and
`saga-forge-runtime` only; JUnit and AssertJ in test scope. `saga-forge-example` adds it in test scope for FR-018's
reference tests. `saga-forge-storage-postgres` adds it in test scope for §8's contract test. The starter and the
Spring example are unaffected.

## Risks

- **R1**: the command interceptor changes a hot path in `EngineCommandGateway`. Mitigation: default interceptor is a
  direct `proceed()`; the runtime's existing suites run unchanged; Property 26 and the 005 command-identity tests
  cover the paths.
- **R2**: reporting "commands of the committed attempt" may surprise a user debugging a retry. Mitigation: the result
  also exposes every attempt's commands, and the failure message names the attempt count.
- **R3**: the value rule in §8 must follow future storage modules (MySQL, MongoDB). Mitigation: the rule is "accepted
  by every supported durable store"; each storage module adds the §8 contract test when it ships.
