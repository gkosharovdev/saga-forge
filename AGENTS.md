# AGENTS.md

## Repo Reality Check
- Current checkout is documentation-heavy: `README.md` and `docs/README.md` are the only project docs present.
- Treat `docs/README.md` as the primary source of architecture and conventions; verify against code when modules are added.

## Big Picture Architecture
- `saga-forge` is a declarative saga/workflow framework for multi-step, event-driven transactions.
- Documented module boundaries (`docs/README.md`):
  - `flow-forge-core`: annotations + workflow engine (framework-agnostic)
  - `flow-forge-storage-postgres`, `flow-forge-storage-mongodb`, `flow-forge-storage-mysql`: pluggable persistence providers
  - `flow-forge-spring-boot-starter`: Spring Boot auto-configuration wrapper
- Intended separation: orchestration logic in core, storage adapters isolated by backend, Spring integration in starter.

## Workflow Model (Project-Specific)
- Workflows are class-based and annotation-driven (`@Workflow`, `@WorkflowId`).
- Steps are event handlers with explicit correlation via `@FlowStep(associationProperty = "...")`.
- Lifecycle boundaries are declared via `@StartWorkflow` and `@EndWorkflow`.
- Compensation is step-targeted via `@workflowCompensation(forStep = "...")`.
- Example pattern from `docs/README.md`: `processPayment -> reserveStock -> requestDelivery`, with compensation methods mapped to the first two steps.

## Integration Points
- Maven coordinates currently documented as `io.github.gkosharovdev:*:0.1.0-SNAPSHOT`.
- Consumers pick `flow-forge-core` + one storage module; Spring apps may use `flow-forge-spring-boot-starter`.
- Communication pattern is event-driven (step method input types are event classes like `OrderCreatedEvent`).

## Agent Working Rules For This Repo
- Prefer edits that preserve annotation-driven declarative style over imperative orchestration code.
- Keep compensation logic explicit and mapped to concrete step names.
- When adding examples/docs, always show `associationProperty` and workflow boundary annotations.
- Do not invent build/test commands until build files (for example, `pom.xml`) are present in the checkout.
- If source modules are later added, update this file with concrete module paths and real build/test/debug commands.

## Fast Discovery Checklist
- Read `README.md` for repository intent.
- Read `docs/README.md` for module map and canonical workflow example.
- If code appears later, map annotations in code back to the documented workflow lifecycle above.
