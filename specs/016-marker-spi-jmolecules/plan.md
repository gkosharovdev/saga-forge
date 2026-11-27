# Implementation Plan: Marker SPI and jMolecules Adapter

**Branch**: `016-marker-spi-jmolecules` | **Date**: 2026-11-27 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/016-marker-spi-jmolecules/spec.md` (revised after 014 merged)

## Summary

Saga Forge learns its domain from markers through one core abstraction instead of 40 scattered annotation reads.
`DomainTypeResolver` (core SPI) answers, for one vocabulary and without any model knowledge, which types are aggregates,
domain events and commands, which executables are command handlers, and which members identify an aggregate; every
answer names the `Marker` behind it. `DomainTypes` (core, final) composes Saga Forge's own annotations (always first,
D1) with added resolvers, detects contradictions (R30), resolves an event's aggregate against the model (R31), and
builds the error texts; it is the only thing the scanner, runtime, transport, starter and fixture consult (D3, FR-004).
The new module `saga-forge-jmolecules` ships `JMoleculesDomainTypeResolver` over jMolecules 2.0 (ddd, events,
cqrs-architecture). `AggregateEventSource` (core SPI) lets the engine take an aggregate's pending events after an
aggregate command handler returned and before its save, and record them exactly like `DomainEvents.record`
(Property 49); Spring Data `@DomainEvents` keep reaching sagas through 014's inbound route, so the starter adds no event
source of its own. A new example module runs the canonical saga over a jMolecules-only domain package (ArchUnit-checked).
Design decisions: [research.md](research.md) R1 to R12.

## Technical Context

**Language/Version**: Java 21 (`maven.compiler.release` 21; CI also on JDK 25)

**Primary Dependencies**: core and runtime: none new (runtime keeps `opentelemetry-api` only). `saga-forge-jmolecules`:
`saga-forge-core` plus `org.jmolecules:jmolecules-ddd` (required), `jmolecules-events` and
`jmolecules-cqrs-architecture` (optional), managed by `org.jmolecules:jmolecules-bom` 2025.0.2 (artifacts 2.0.1) in
the root POM. Starter: `saga-forge-jmolecules` as an optional dependency. Example: Spring Boot 4.1.2, `JdbcClient`.

**Storage**: unchanged (Postgres via `saga-forge-storage-postgres`); no schema change. `StepRecord`'s touched
`AggregateReference` stores a jMolecules aggregate's class name exactly as today's.

**Testing**: JUnit 5, AssertJ, jqwik (Properties 48 and 49), ArchUnit (`DomainAnnotationAccessTest`,
`DomainDependsOnlyOnJMoleculesTest`), Testcontainers Postgres for the example (`@Tag("container")`).

**Target Platform**: JVM library; Spring Boot 4.1 applications through the starter.

**Project Type**: multi-module Maven library.

**Performance Goals**: no measurable change: every reflective answer is cached per class (`ClassValue`), as the
identifier field lookup is today; startup cost grows by one resolver pass per model class.

**Constraints**: built-in-only models see byte-identical behaviour and error texts (FR-002, FR-011, SC-003); core has
zero third-party dependencies (Principle I); jMolecules never leaks into core, runtime or starter's required
dependencies (FR-013).

**Scale/Scope**: about 40 call sites in six modules rewritten to `DomainTypes`; five new core types, two new rules, two
new properties, one new library module, one new example module.

## Constitution Check

*GATE: passed before Phase 0; re-checked after Phase 1 (below).*

| Principle | Check | Status |
|---|---|---|
| I. Zero-Dependency Core | `core.spi.model` and `AggregateEventSource` are plain JDK; jMolecules only in `saga-forge-jmolecules`; the starter depends on it optionally. G-002-05 lists "a jMolecules adapter" as an accepted candidate; the pending Principle I wording amendment is unchanged by this feature (still triggered by the first core/runtime dependency). | Pass |
| II. Annotation-Driven, Declarative Style | Domain roles stay markers on types; saga semantics stay Saga Forge's annotations (D2). The rule against "aliases into third-party annotations" targets framework-owned *behaviour* (retry) borrowed from another library; recognising another library's *domain stereotypes* as equivalent markers, in an adapter module, is the interop the constitution's own "jMolecules consideration" anticipates, and no Saga Forge attribute (`delivery`, `retry`) is aliased (D12). No fluent registration: hosts add vocabularies, never roles. Principle II's annotation list still names `@ReadModel`; FR-032 corrects it by a PATCH amendment. | Pass (justified) |
| III. Formal Spec Conformance | Properties 16 and 17 restated over "aggregate marker"/"identifier marker" with unchanged strength; new rules R30, R31 and Properties 48 (vocabulary equivalence) and 49 (harvested = recorded) in `.kiro/specs/saga-forge-framework/design.md`, each with a property test. 014's Property 47 is reused, not changed. | Pass |
| IV. Explicit Narrow Scope | No storage or transport added. jMolecules is an integration library, not a transport or store. | Pass |
| V. Documentation Hierarchy | architecture.md "jMolecules consideration" replaced (closes G-002-04), README modules table, guide page, SKILL.md, AGENTS snippet, `llms-full.txt`, AGENTS.md status, memory.md gaps G-016-01 to G-016-06. | Pass |
| Development Workflow: composition | `DomainTypes` holds its resolvers; the built-in vocabulary is one more resolver, no class hierarchy. `IdentifierMember` is a final class over a `Member` and a reader. | Pass |
| Development Workflow: abstractions tested first | `DomainTypeResolver` has three implementations, two of them shipped (built-in, jMolecules) and one test-only (R12); `AggregateEventSource` has the example's `OrderEventSource` and test doubles in runtime and fixture tests. | Pass |
| Development Workflow: familiarity | Names rated in research R2 and R7; `ModelVocabulary` rejected as boutique. | Pass |

Post-design re-check (after data-model and contracts): unchanged, all pass. No Complexity Tracking entries.

## Project Structure

### Documentation (this feature)

```text
specs/016-marker-spi-jmolecules/
├── spec.md
├── plan.md              # this file
├── research.md          # R1 to R12
├── data-model.md        # types, invariants, rules R30/R31, Properties 48/49
├── quickstart.md        # validation scenarios
├── contracts/
│   ├── domain-types-spi.md        # DomainTypeResolver, DomainTypes, Marker, IdentifierMember, DomainRole
│   ├── aggregate-event-source.md  # AggregateEventSource and the harvest point
│   ├── jmolecules-module.md       # JMoleculesDomainTypeResolver mapping
│   └── host-configuration.md      # runtime/transport/scanner/fixture builders, starter beans and property, errors
├── checklists/requirements.md
└── tasks.md             # /speckit-tasks
```

### Source Code (repository root)

```text
saga-forge-core/src/main/java/io/github/gkosharovdev/sagaforge/core/
├── spi/AggregateEventSource.java                       # new SPI
├── spi/model/                                          # new package
│   ├── DomainTypeResolver.java                         # SPI, one vocabulary
│   ├── DomainTypes.java                                # composition, caches, R30/R31, describe()
│   ├── DomainRole.java  Marker.java  IdentifierMember.java
│   └── SagaForgeAnnotations.java                       # package-private built-in resolver
├── scanner/ReflectiveAnnotationScanner.java            # takes DomainTypes; R30, R31; parametrised texts
└── event/DomainEvents.java                             # marker check moves to the recorder; capture(DomainTypes, ..)
saga-forge-core/src/test/.../core/spi/model/            # TestDomainTypeResolver + test annotations (R12), DomainTypesTest
saga-forge-core/src/test/.../core/scanner/              # ScannerErrorTextsTest (golden), VocabularyRulesTest (R30/R31)

saga-forge-runtime/src/main/java/.../runtime/
├── SagaRuntime.java                                    # Builder.domainTypes, Builder.aggregateEventSource
└── internal/  EngineCommandGateway (harvest point), Reflection, RunSteps, EventRouter, UnitOfWork, RunCompensation,
               EngineEventPublisher, StartupChecks, EngineRuntime, SagaModel (step event → aggregate), AggregateAccess
saga-forge-runtime/src/test/.../runtime/                # HarvestedEventsTest, HarvestPropertyTest (P49),
                                                        # VocabularyEquivalencePropertyTest (P48), ThirdVocabularyTest,
                                                        # DomainAnnotationAccessTest (ArchUnit)

saga-forge-transport-inmemory/.../InMemoryTransport.java, internal/OrderingKeys.java   # Builder.domainTypes

saga-forge-jmolecules/                                  # new module
├── pom.xml
├── README.md
└── src/main/java/io/github/gkosharovdev/sagaforge/jmolecules/JMoleculesDomainTypeResolver.java
    src/test/java/.../jmolecules/                       # mapping tests, scanner rules over jMolecules, canonical saga

saga-forge-spring-boot-starter/.../spring/
├── runtime/SagaForgeDomainTypesConfiguration.java      # DomainTypes bean, jMolecules auto-enable
├── runtime/SagaModelCollector.java, SagaRuntimeConfiguration.java, host/SpringDataAggregateResolver.java
├── transport/InMemoryTransportConfiguration.java       # passes DomainTypes
├── actuator/...                                        # vocabularies + event sources in `sagaforge`
└── README.md, additional-spring-configuration-metadata.json   # saga-forge.jmolecules.enabled

saga-forge-test/.../test/SagaFixture.java, Model.java   # Builder.domainTypes, Builder.aggregateEventSource

saga-forge-example-jmolecules/                          # new module (R11)
├── src/main/java/.../example/jmolecules/domain/        # jMolecules-only
├── src/main/java/.../example/jmolecules/application/   # saga, repository, event source, placement
└── src/test/java/...                                   # ArchUnit, fixture, container test

docs/architecture.md, docs/guide/(vocabularies.md, validation-rules.md, model-reference.md, limitations.md),
docs/agents/saga-forge/SKILL.md, docs/agents/AGENTS-snippet.md, llms-full.txt, README.md, AGENTS.md,
.kiro/specs/saga-forge-framework/design.md, .specify/memory/(memory.md, roadmap.md, constitution.md via /speckit-constitution)
```

**Structure Decision**: the existing multi-module layout plus two modules, `saga-forge-jmolecules` (published) and
`saga-forge-example-jmolecules` (not published). The release profile adds the former (FR-035).

## Implementation Order

Ordered so that each step leaves the whole build green and every built-in text unchanged.

1. **Pin behaviour.** `ScannerErrorTextsTest` (golden texts of all current rules) and `DomainAnnotationAccessTest`
   (ArchUnit, initially listing today's call sites as known violations).
2. **Core types.** `core.spi.model` with the built-in resolver; `DomainTypesTest`; `TestDomainTypeResolver` (R12).
3. **Scanner.** Constructor with `DomainTypes`; rewrite role and identifier reads; parametrised texts (golden test stays
   green); R30, R31; Properties 16/17 restated; tests over the test vocabulary.
4. **Runtime.** `Builder.domainTypes`; rewrite every R1 call site; `SagaModel` resolves step event aggregates; marker
   check of `DomainEvents.record` moves to the recorder; `capture(DomainTypes, ..)`; ArchUnit allow-list emptied.
5. **Harvesting.** `AggregateEventSource`, `Builder.aggregateEventSource`, harvest in `EngineCommandGateway`; FR-025
   error; Property 49 test.
6. **Transport and fixture.** `InMemoryTransport.Builder.domainTypes`, `OrderingKeys`; fixture builder methods, `Model`,
   harvest parity case in `FixtureParityTest`; Property 48 test over the test vocabulary (SC-007).
7. **`saga-forge-jmolecules`.** Resolver, mapping tests, scanner rules over jMolecules markers (SC-004), canonical saga
   over a hand-assembled runtime.
8. **Starter.** `DomainTypes` bean, auto-enable and property, collector/resolver/transport wiring, event source beans,
   Actuator listing, README + metadata (`ReadmePropertiesTest`); FR-024 test (event source plus Spring Data
   `@DomainEvents` aggregate, default mode and Modulith interop); FR-036 test (jMolecules event through
   `ApplicationEventPublisher` starts a saga).
9. **Example.** `saga-forge-example-jmolecules` (R11), ArchUnit, fixture tests, container test.
10. **Docs and records.** design.md, architecture.md, guide, agents docs, `llms-full.txt`, README, AGENTS.md, memory.md
    gaps, roadmap, constitution PATCH; release build with Javadoc (`mvn -P release -Dgpg.skip -DskipTests install`);
    whole build with container suites.

## Risks

- **Text drift in the scanner refactor.** Mitigated by step 1's golden test before any change.
- **Generic type argument of `Association<A, ID>` erased** on a raw or wildcard member: R31 fails with "cannot read the
  aggregate type of member X; declare `Association<A, ID>` with concrete types" rather than guessing.
- **Record accessors as identifiers.** A jMolecules record aggregate exposes `id()`, not `getId()`, unless it
  implements `AggregateRoot` (which requires `getId()`); `@Identity` on the record component is read from the backing
  field (Java propagates component annotations to the field when the annotation targets `FIELD`).
- **014 interplay.** An event source and Spring Data's publication on the same aggregate: covered by taking before the
  save (D10) and by the FR-024 test under both application-event modes.

## Complexity Tracking

No constitution violations to justify.
