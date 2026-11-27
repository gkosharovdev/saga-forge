<!--
Sync Impact Report
Version change: 1.1.0 → 1.1.1 (PATCH — correction; no principle semantics changed)
Modified principles:
  - II. Annotation-Driven, Declarative Style — removed `@ReadModel` from the annotation list: no such annotation
    exists in saga-forge-core (memory.md G-002-04; specs/016-marker-spi-jmolecules FR-032). Domain markers may also
    come from an enabled vocabulary (016), which keeps the model declarative; wording unchanged.
Modified sections: none
Added sections: none
Removed sections: none
Templates requiring updates: none (.specify/templates/* reference principles by number only)
Dependent guidance updated: docs/architecture.md (@ReadModel reads as future), memory.md G-002-04 closed.
Follow-up TODOs: none
Previous report (1.1.0): MINOR — Development Workflow materially expanded
Modified principles: none
Modified sections:
  - Development Workflow — added three rules: composition over inheritance in class-level design (a class
    hierarchy is justified in plan.md); every newly invented abstraction is exercised by a test before it
    is proposed; abstractions are intention-revealing and rated for community familiarity, with boutique
    ones replaced by a recognisable alternative.
Added sections: none
Removed sections: none
Templates requiring updates: none (.specify/templates/* reference principles by number only)
Dependent guidance updated: AGENTS.md "Agent Working Rules For This Repo" (same rules, plus loading the
  effective-java, clean-code-java and solid-design-review skills before class-level design).
Follow-up TODOs: none
Previous report (1.0.1): PATCH — clarification only; no principle semantics changed.
Modified principles (1.0.1):
  - II. Annotation-Driven, Declarative Style — annotation list extended with the annotations introduced by
    specs/002-transactional-step-sequence (`@Aggregate`, `@AggregateIdentifier`, `@CommandHandler`,
    `@AggregateLoader`, `@AggregateSaver`); principle wording unchanged.
Modified sections:
  - Technical Constraints — replaced the stale "`SagaContext` tracks step history" line with its current
    meaning: named business state held in a separate `SagaContextRecord` (keyed by sagaId, stored via
    `SagaContextStore`) that survives unit-of-work rollback and is queryable by later steps.
Added sections: none
Removed sections: none
Templates requiring updates: none (.specify/templates/* reference principles by number only)
Follow-up TODOs: none
Previous report (1.0.0): initial adoption — Core Principles (I-V), Technical Constraints, Development
Workflow, Governance.
-->

# Saga Forge Constitution

## Core Principles

### I. Zero-Dependency Core
`saga-forge-core` (annotations, scanner, `SagaContext`/`SagaLifecycle`, SPI contracts) MUST have zero
runtime dependencies on Spring, a specific datastore, or a specific transport. SPI contracts
(`StorageProvider`, `EventDispatcher`) MUST remain transport- and storage-agnostic. Framework-specific
wiring (Spring Boot autoconfiguration, Postgres/MySQL/MongoDB storage, Kafka/in-memory transport) lives
only in the dedicated `saga-forge-storage-*`, `saga-forge-transport-*`, and
`saga-forge-spring-boot-starter` modules — never leaked back into core.
**Rationale**: the project's value proposition is a high-level, portable saga abstraction; any
core-level coupling to one stack defeats that goal and blocks future integrations.

### II. Annotation-Driven, Declarative Style
Sagas are modeled declaratively via annotations (`@Saga`, `@SagaId`, `@SagaStep`, `@StartSaga`,
`@EndSaga`, `@SagaCompensation`, `@DomainEvent`, `@Command`, `@Aggregate`,
`@AggregateIdentifier`, `@CommandHandler`, `@AggregateLoader`, `@AggregateSaver`), not imperative
orchestration code. New capabilities (retry, idempotency, failure classification, etc.) MUST be
expressed as framework-owned annotation attributes or SPI extension points, not as aliases into
third-party annotations (e.g. a third-party retry annotation) and not as hand-rolled orchestration glue
that competes with the declarative model.
**Rationale**: declarative annotations are what let the framework raise the abstraction level above
hand-rolled, project-specific saga plumbing — this is the explicit purpose of the project.

### III. Formal Spec Conformance
Changes to annotation semantics, validation rules, or `SagaContext`/`SagaLifecycle` contracts MUST be
validated against the correctness properties already specified in
`.kiro/specs/saga-forge-framework/design.md` and `.kiro/specs/saga-context-lifecycle/design.md` before
being merged. New feature work MUST follow the Speckit flow (`/speckit-specify` → `specs/NNN-*/spec.md`
→ `/speckit-plan` → `/speckit-tasks` → `/speckit-implement`) so that design intent is captured before
implementation, consistent with the project's documented spec-driven development approach.
**Rationale**: the annotation model and lifecycle contracts are the framework's public surface; informal,
undocumented changes to them risk silent behavior breaks for every consumer.

### IV. Explicit, Narrow Scope
Storage and transport implementations are limited to Postgres, MySQL, MongoDB (storage) and
Kafka, in-memory (transport). No additional storage or transport technology MAY be added without an
explicit scope-expansion decision raised with the project owner first. Feature gaps MUST be documented
as abstract framework capabilities (see `docs/architecture.md#feature-gaps`), never as implementation
prescriptions tied to one consumer's internal design.
**Rationale**: an unbounded module surface dilutes maintenance effort and the project's identity as a
focused, reusable abstraction rather than a grab-bag of integrations.

### V. Documentation Hierarchy as Source of Truth
`README.md` (pitch + canonical example) → `docs/architecture.md` (design + vocabulary) →
`docs/use-cases.md` (worked examples) → actual code in `saga-forge-core` is the authoritative order for
resolving any conflict. `docs/Saga_Framework_Epic.md`, `docs/Saga_User_Stories.md`, and
`docs/Saga_Technical_Tasks.md` are superseded historical artifacts and MUST NOT be treated as current
design. DDD vocabulary (Command, Domain Event, Aggregate, Policy, Projection/Read Model, Saga, Step,
Compensation) MUST be used consistently across code, docs, and specs per
`docs/architecture.md#vocabulary`.
**Rationale**: multiple overlapping planning artifacts exist from an earlier naming scheme; without an
explicit precedence order, contributors and agents will act on stale information.

## Technical Constraints

- Module boundaries follow the Module Map in `AGENTS.md`: `saga-forge-core` has no dependents' concerns
  leaking in; `saga-forge-spring-boot-starter` auto-detects an available datastore and transport on the
  classpath rather than requiring users to hand-assemble a `SagaRuntimeConfig`.
- `@SagaStep` currently has no retry/backoff attributes. Any such addition MUST update
  `.kiro/specs/saga-forge-framework/` first and MUST be framework-owned (Principle II).
- `SagaContext` holds named, type-safe business state for one saga instance. Its values live in a
  separate `SagaContextRecord` keyed by sagaId and stored via `SagaContextStore` (not in
  `SagaInstance.state`, which holds only the saga class's own fields), so they survive a unit-of-work
  rollback and are queryable by later steps of the same instance. Step history is tracked by the
  framework on `SagaInstance` (`StepRecord`), not by `SagaContext`. Expanding this into a general
  queryable process-level state store across instances remains a feature gap, not an incidental
  change — see `docs/architecture.md#feature-gaps`.

## Development Workflow

- All new feature work is driven through Speckit: `/speckit-specify` creates `specs/NNN-feature-name/`,
  correlated to a same-named git branch, followed by `/speckit-plan` and `/speckit-tasks` before
  `/speckit-implement`.
- `.kiro/specs/saga-forge-framework/` and `.kiro/specs/saga-context-lifecycle/` remain the historical,
  pre-Speckit baseline for already-implemented work. They are not migrated into the `specs/NNN-*`
  convention retroactively.
- Examples and docs that introduce or modify a saga MUST show `associationProperty` and the
  start/end lifecycle annotations, per `AGENTS.md`.
- Class-level design favours composition over inheritance. A new type reuses behaviour by holding and
  delegating to collaborators, not by extending a concrete class; inheritance is reserved for types
  designed and documented for it (sealed hierarchies, value variants). A plan that introduces a class
  hierarchy MUST justify it in `plan.md`.
- A newly invented abstraction (interface, SPI, abstract type, extension point) MUST be exercised by a
  test before it is proposed: at least one concrete use and a test against it (a test double or a second
  implementation), so the abstraction's shape is proven by a caller rather than assumed. An abstraction
  with no test-backed caller is not proposed in `plan.md`, `tasks.md` or code.
- Abstractions and interfaces MUST be intention-revealing and recognisable to the community. When a
  request is mapped to class design and an invocation chain, the plan rates each proposed abstraction's
  name and shape by how widely it is known (established patterns, well-known libraries and frameworks,
  common engineering vocabulary such as Repository, Gateway, Outbox, Policy, Handler, Resolver), since a
  familiar abstraction is an understandable one. A boutique abstraction or name that appears in no known
  community codebase or common usage is avoided, and the plan proposes a recognisable alternative.

## Governance

This constitution supersedes ad hoc practices documented elsewhere when they conflict. Amendments
require: a documented rationale, an explicit version bump per the policy below, and update of any
dependent guidance (`AGENTS.md`, `docs/architecture.md`) in the same change.

Versioning policy (semantic versioning applied to this document):
- MAJOR: backward-incompatible principle removal or redefinition.
- MINOR: new principle or materially expanded section.
- PATCH: wording clarifications and non-semantic fixes.

All Speckit-driven work (`/speckit-plan`, `/speckit-implement`) MUST verify compliance with the Core
Principles above before merge. Complexity that violates Principle I or IV must be explicitly justified
in the relevant `spec.md`/`plan.md` or rejected.

**Version**: 1.1.1 | **Ratified**: 2026-10-11 | **Last Amended**: 2026-11-27
