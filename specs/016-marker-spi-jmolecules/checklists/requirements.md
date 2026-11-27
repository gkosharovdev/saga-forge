# Specification Quality Checklist: Marker SPI and jMolecules Adapter

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-11-27
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs)
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain
- [x] Requirements are testable and unambiguous
- [x] Success criteria are measurable
- [x] Success criteria are technology-agnostic (no implementation details)
- [x] All acceptance scenarios are defined
- [x] Edge cases are identified
- [x] Scope is clearly bounded
- [x] Dependencies and assumptions identified

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria
- [x] User scenarios cover primary flows
- [x] Feature meets measurable outcomes defined in Success Criteria
- [x] No implementation details leak into specification

## Notes

- Saga Forge is a library whose stakeholders are application developers. As in specs 011 to 017, the spec names the
  public markers (Saga Forge's and jMolecules' annotations) and the modules involved, because they are the feature's
  user-facing surface; class design, SPI method signatures and wiring are left to `/speckit-plan`. Content-quality
  items are judged against that convention.
- Success criteria name jMolecules and Spring only where the feature's subject is integrating them (SC-001, SC-004,
  SC-006); the outcomes themselves are counts and pass/fail checks.
- Three clarifications were resolved on 2026-11-27 (spec "Clarifications"): FR-016 association member names the
  aggregate (D11), FR-024 Saga Forge is the only publisher of harvested Spring Data events (D10), FR-028 outbox
  delivery keeps Saga Forge's `@Command` (D12). All items pass; ready for `/speckit-plan`.
- Revalidated 2026-11-27 after rebasing on 014 (publish once): Spring Data `@DomainEvents` now reach sagas through 014's
  inbound route, so the starter's Spring Data event source was dropped (D9, FR-023) and the FR-024 answer narrowed to
  "an event source clears before the save" (D10). New properties renumbered to 48 and 49 (014 took 47). FR-036/FR-037
  tie the vocabulary to 014's `SagaRuntime.eventTypes()` and ordering key. jMolecules ships no event source (D5,
  FR-019, G-016-03). All items still pass.
