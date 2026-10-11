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
  `SagaValidationResult`, `SagaContext`/`SagaLifecycle`, SPI contracts (`StorageProvider`, `EventDispatcher`) —
  all in `saga-forge-core`.
- Not implemented: the saga engine runtime, any `StorageProvider`/`EventDispatcher` implementation, and Spring
  Boot autoconfiguration. The `saga-forge-storage-*`, `saga-forge-transport-*`, and
  `saga-forge-spring-boot-starter` modules exist only as placeholder POMs.
- Do not assume engine/autoconfiguration behavior exists just because a module directory exists. Check for
  actual `.java` source before relying on it.

## Module Map
- `saga-forge-core` — annotations (`@Saga`, `@SagaId`, `@SagaStep`, `@StartSaga`, `@EndSaga`,
  `@SagaCompensation`, `@DomainEvent`, `@Command`, `@ReadModel`), scanner, `SagaContext`/`SagaLifecycle`, SPI.
  Zero dependencies.
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
- Steps are event handlers: `@SagaStep(associationProperty = "...")` on a method taking exactly one
  `@DomainEvent`-annotated parameter, returning a `@Command`-annotated type (or `void` for terminal steps).
- Lifecycle boundaries: `@StartSaga` and `@EndSaga` must co-occur with `@SagaStep` on the same method.
- Compensation is step-targeted: `@SagaCompensation(forStep = "...")`, where `forStep` must name an existing
  `@SagaStep` method on the same class.
- Canonical example: `saga-forge-example`'s `OrderProcessingSaga`
  (`handleOrderCreated -> processPayment -> completeOrder`, with `compensatePayment` mapped to `processPayment`).
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
- `.specify/memory/constitution.md` is still the unfilled template; run `/speckit-constitution` before relying
  on it for governing principles.

## Known Gaps to Respect When Extending
- See `docs/architecture.md#feature-gaps` for the full backlog: queryable process-level state (highest
  priority), idempotent step execution, per-step retry with failure classification, transaction boundary
  relative to the triggering dispatch, and failure categorization with compensation dispatch.
- `@SagaStep` has no retry/backoff attributes yet. Don't silently add this without updating the spec in
  `.kiro/specs/saga-forge-framework/` first — retry attributes must be framework-owned, not aliased into a
  third-party retry annotation.
- No compensation-vs-intervention-required outcome categorization exists in code yet.
- `SagaContext` tracks step *history* (what happened), not arbitrary associated business state a later step
  can query — see "Queryable process-level state" in `docs/architecture.md#feature-gaps`.
- jMolecules integration is a documented consideration (`docs/architecture.md`), not yet decided or built.

## Agent Working Rules For This Repo
- Prefer edits that preserve the annotation-driven declarative style over imperative orchestration code.
- Keep compensation logic explicit and mapped to concrete step names via `forStep`.
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
