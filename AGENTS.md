# AGENTS.md

## Repo Reality Check
- Source of truth order: `README.md` (pitch + canonical example) → `docs/architecture.md` (design + vocabulary) →
  `docs/use-cases.md` (worked examples) → actual code in `saga-forge-core`.
- `docs/Saga_Framework_Epic.md`, `docs/Saga_User_Stories.md`, `docs/Saga_Technical_Tasks.md` are historical
  planning artifacts from an earlier naming scheme. They are superseded wherever they conflict with the docs
  above — do not treat them as current design.
- `.kiro/specs/saga-forge-framework/` and `.kiro/specs/saga-context-lifecycle/` hold the formal
  requirements/design specs backing what is actually implemented in `saga-forge-core`. They are accurate and
  worth reading for implementation-level detail (validation rules, SPI contracts, correctness properties).

## Current Status
- Implemented and tested: annotation model, `AnnotationScanner`/`ReflectiveAnnotationScanner`,
  `SagaValidationResult`, SPI contracts (`StorageProvider`, `EventDispatcher`) — all in `saga-forge-core`.
- Contracts only (`core.lifecycle`): `SagaContext` (named, type-safe per-instance business state via
  `getState`/`putState`/`updateStateAttribute`; null values rejected) and `SagaLifecycle`
  (`start`/`advance`/`complete`), plus `SagaStateTypeMismatchException`, `NonStructuredStateValueException`,
  `DuplicateSagaInstanceException`, `InvalidSagaTransitionException`. Tested via test-only doubles
  (`InMemorySagaContext`/`InMemorySagaLifecycle`); no production implementation ships — same posture as
  `StorageProvider`/`EventDispatcher`.
- Contracts only, added by `specs/002-transactional-step-sequence`: `core.command` (`CommandGateway`,
  `NoCommandHandlerException`, `AggregateNotFoundException`), `core.event` (`DomainEvents.record`/`capture`,
  `EventPublisher`), SPI `AggregateResolver`/`AggregateReference`, `HandlerInstanceProvider`,
  `SagaContextStore`/`SagaContextRecord`, `SagaInstance` with per-step `StepRecord`/`StepStatus`, `outcome` and
  `SagaFailure`; `SagaLifecycle` gains `recordStepCompleted`, `complete(sagaId, outcomeStep)` and `fail`. The
  scanner (implemented) gains `validateAll` → `ValidationReport`. Execution semantics are proven only by the
  test-only `SagaTestHarness` in `saga-forge-core/src/test/.../core/execution/harness/` — it is not an engine.
- Not implemented: the saga engine runtime, any `StorageProvider`/`EventDispatcher` implementation, and Spring
  Boot autoconfiguration. The `saga-forge-storage-*`, `saga-forge-transport-*`, and
  `saga-forge-spring-boot-starter` modules exist only as placeholder POMs.
- Do not assume engine/autoconfiguration behavior exists just because a module directory exists. Check for
  actual `.java` source before relying on it.

## Module Map
- `saga-forge-core` — annotations (`@Saga`, `@SagaId`, `@SagaStep`, `@StartSaga`, `@EndSaga`,
  `@SagaCompensation`, `@DomainEvent`, `@Command`, `@CommandHandler`, `@Aggregate`, `@AggregateIdentifier`,
  `@AggregateLoader`, `@AggregateSaver`), scanner, `SagaContext`/`SagaLifecycle` contracts, `CommandGateway`,
  `DomainEvents`/`EventPublisher`, SPI. Zero dependencies.
- `saga-forge-spring-boot-starter` — target: autoconfiguration that detects an available datastore and
  transport on the classpath and wires the engine. No `SagaEngineConfig` for users to hand-assemble.
- `saga-forge-storage-postgres` / `-mysql` / `-mongodb` — target: `StorageProvider` implementations, one per
  backend.
- `saga-forge-transport-inmemory` — target: `EventDispatcher` over Spring `ApplicationEventPublisher`.
- `saga-forge-transport-kafka` — target: `EventDispatcher` over Kafka. These two transports are the only ones
  in scope — do not add RabbitMQ, SQS, etc. without an explicit decision to expand scope.
- `saga-forge-example` — `OrderProcessingSaga` is the canonical reference pattern; keep it in sync with
  whatever the annotation model currently supports.

## Saga Model (Project-Specific)
- Sagas are class-based and annotation-driven: `@Saga` on the class, `@SagaId` on exactly one `String` field.
- Optional business state: at most one `SagaContext`-typed field (recognized by type, no annotation) —
  enforced by the scanner (Property 9 in `.kiro/specs/saga-forge-framework/design.md`).
- Steps are event handlers: `@SagaStep(associationProperty = "...")` on a method taking exactly one
  `@DomainEvent`-annotated parameter and returning `void` (Property 10); no two steps take the same event type
  (Property 11). Steps run in event order, not declaration order.
- Steps dispatch zero or more commands via `commandGateway.send(...)` on an optional `CommandGateway` field
  (recognized by type, no annotation, at most one — Property 13), like the `SagaContext` field.
- Lifecycle boundaries: exactly one `@StartSaga`, one or more `@EndSaga` steps (each a named outcome; the
  instance records which one ended it); both must co-occur with `@SagaStep` on the same method.
- `@CommandHandler` hosts: `@Aggregate` instance methods (target loaded by the command's `@AggregateIdentifier`
  via a client `@AggregateLoader` or a host `AggregateResolver`, saved via `@AggregateSaver`/resolver/client
  UoW), `@Aggregate` constructors/static factories (creation), or plain service methods — never on a `@Saga`
  (Property 12). Exactly one `@Command` param per handler; one handler per command type (Properties 14–15).
- Events: `@DomainEvent(aggregate = X.class)` where `X` is `@Aggregate`, with exactly one
  `@AggregateIdentifier` field (Properties 16–17). Handlers record them with static
  `DomainEvents.record(...)` (no base class/interface on aggregates); outside a unit of work, publish via
  `EventPublisher`.
- Saga-scoped unit of work: commits at the first `@EndSaga` step or at a wait point (no queued event left for
  the instance); any failure rolls the whole unit of work back and the saga is `FAILED`.
  `SagaContext` values live in a separate `SagaContextRecord` and survive the rollback.
- Validate a whole model with `AnnotationScanner.validateAll(...)` → `ValidationReport` (cross-class rules,
  Properties 14–20); `validate(...)` covers single-saga rules only.
- Compensation is step-targeted: `@SagaCompensation(forStep = "...")`, where `forStep` must name an existing
  `@SagaStep` method on the same class.
- Canonical example: `saga-forge-example`'s `OrderProcessingSaga` (`handleOrderCreated` (`@StartSaga`) →
  `processPayment` → `completeOrder` (`@EndSaga`), all on `associationProperty = "orderId"`):
  `ProcessPaymentCommand` handled by `PaymentService` (service), `ReserveStockCommand` handled by `Order`
  (aggregate instance method, loaded/saved through `OrderRepository`); `compensatePayment` mapped to
  `processPayment`.
- Transport is event-driven (choreography-shaped at delivery); the saga instance keeps durable,
  orchestration-shaped process state per `docs/architecture.md`. Don't conflate the two — the SPI contracts are
  transport-agnostic by design, so don't leak Kafka- or in-memory-specific assumptions into `saga-forge-core`.

## Spec-Driven Development (Speckit)
- saga-forge uses GitHub Speckit for all *new* feature work, wired in via Claude Skills under
  `.claude/skills/speckit-*`: `/speckit-constitution`, `/speckit-specify`, `/speckit-plan`, `/speckit-tasks`,
  `/speckit-implement`, `/speckit-converge` (core flow), plus `/speckit-clarify`, `/speckit-analyze`,
  `/speckit-checklist` (enhancement skills).
- New features follow `specs/NNN-feature-name/` (spec.md → plan.md → tasks.md, plus research.md/data-model.md/
  quickstart.md/contracts/ as needed), correlated to a same-named git branch. Start with `/speckit-specify`.
- `.kiro/specs/saga-forge-framework/` and `.kiro/specs/saga-context-lifecycle/` are the pre-Speckit historical
  baseline documenting what's already implemented (see "Repo Reality Check" above). They are not being
  migrated or regenerated into the `specs/NNN-*` convention — leave them as-is as accurate historical record.
- `.specify/memory/constitution.md` is ratified and defines governing principles I–V (Zero-Dependency
  Core, Annotation-Driven Declarative Style, Formal Spec Conformance, Explicit Narrow Scope, Documentation
  Hierarchy as Source of Truth). Plans and tasks cite them as "Constitution Principle N"; amend it via
  `/speckit-constitution`.

## Known Gaps to Respect When Extending
- See `docs/architecture.md#feature-gaps` for the full backlog: queryable process-level state (highest
  priority), idempotent step execution, per-step retry with failure classification, transaction boundary
  relative to the triggering dispatch, and failure categorization with compensation dispatch.
- `@SagaStep` has no retry/backoff attributes yet. Don't silently add this without updating the spec in
  `.kiro/specs/saga-forge-framework/` first — retry attributes must be framework-owned, not aliased into a
  third-party retry annotation.
- No compensation-vs-intervention-required outcome categorization exists in code yet.
- `SagaContext` values are a separate `SagaContextRecord` behind `SagaContextStore` (not `SagaInstance.state`,
  which holds only the saga class's own fields), but no engine populates the `SagaContext`/`CommandGateway`
  fields or round-trips either record through a concrete store across dispatches.
- Deferred to the engine feature: the transaction SPI that brackets a saga-scoped unit of work; cross-process
  one-unit-of-work-per-instance enforcement (optimistic version on the instance record); resolving
  non-command `@CommandHandler` parameters; the startup check that every aggregate with instance-method
  handlers has a loader (the scanner can't see host `AggregateResolver`s, so it can't enforce this).
- Deferred to the Spring starter: a Spring Data repository adapter behind `AggregateResolver`.
- Compensation semantics (when/how `@SagaCompensation` runs) belong to a separate spec; a `FAILED` saga's
  committed effects currently stay in place.
- Step retry needs its own design; today a rollback ends the saga `FAILED`. Don't preclude retrying a failed
  step with the `SagaContext` values it already wrote.
- Deferred: whether a `@StartSaga` step may also be a `@CommandHandler`. Dropped for now: programmatic abort
  (e.g. `SagaLifecycle.abort()`) — don't add it without a spec.
- No waiting timeouts: a saga waits indefinitely for its next event; deadlines stay a separate scheduled
  process.
- jMolecules integration is a documented consideration (`docs/architecture.md`), not yet decided or built.

## Agent Working Rules For This Repo
- Prefer edits that preserve the annotation-driven declarative style over imperative orchestration code.
- Keep compensation logic explicit and mapped to concrete step names via `forStep`.
- Never show a step returning a command; steps dispatch through `CommandGateway`.
- Event-driven aggregates/handlers record events via `DomainEvents.record`; don't require a Saga Forge
  supertype on aggregates.
- When adding examples or docs, always show `associationProperty` and the start/end lifecycle annotations.
- Never introduce a storage or transport beyond Postgres/MySQL/MongoDB and Kafka/in-memory without raising it
  with the user first — this is an explicit scope boundary, not an oversight.
- Validate changes against the correctness properties already specified in
  `.kiro/specs/saga-forge-framework/design.md` and `.kiro/specs/saga-context-lifecycle/design.md` before
  changing annotation semantics.
- Follow the DDD vocabulary consistently: Command, Domain Event, Aggregate, Policy, Projection/Read Model,
  Saga, Step, Compensation — see `docs/architecture.md#vocabulary` for definitions.

## Fast Discovery Checklist
- Read `README.md` for the pitch and the canonical example.
- Read `docs/architecture.md` for module responsibilities, autoconfiguration detection matrix, and vocabulary.
- Read `docs/use-cases.md` for worked multi-step examples including failure/retry/compensation.
- Cross-check any annotation claim against the actual source in `saga-forge-core` before trusting a doc.
