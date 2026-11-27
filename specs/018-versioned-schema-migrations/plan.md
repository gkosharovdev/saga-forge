# Implementation Plan: Versioned Schema Migrations

**Branch**: `018-versioned-schema-migrations` | **Date**: 2026-11-27 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/018-versioned-schema-migrations/spec.md`

## Summary

Saga Forge's Postgres schema ships as five Flyway versioned migrations, one per schema-changing feature in commit order
(V1 = `schema.sql` of 0.1.0, then 011, 017, 013, 015; research R1). They live under the package path
`io/github/gkosharovdev/sagaforge/storage/postgres/migration`, not under `db/migration`, because Flyway scans that
recursively and the application's own Flyway would pick them up (R2). Saga Forge's applied versions go to its own
history table, `saga_forge_schema_history`. The storage module stays Flyway-free and adds a public, read-only schema
check (`PostgresSchemaValidator`). The check compares `pg_catalog` with a shipped JSON snapshot per version, matching
constraints and indexes by meaning (R5). The same comparison detects the level of a database that has the tables but
no history (R6).

The starter gains `initialize-schema=validate` and `migrate`. `migrate` drives Flyway's Java API with Saga Forge's
location and table, after every application database initialiser (`@DependsOnDatabaseInitialization`, R7). It relies
on Flyway's Postgres advisory lock for concurrent starts (R8), and validates afterwards. `schema.sql` and the upgrade
scripts keep shipping. Container tests prove that every path (migrations, `schema.sql`, each released level plus its
upgrades) ends at the same schema (R3), and checksums freeze released versions (R4).

## Technical Context

**Language/Version**: Java, `maven.compiler.release` 21 (CI also on JDK 25)

**Primary Dependencies**:
- **Storage module**: no new dependency. It keeps Jackson 3 (used for the snapshots) and the JDBC driver. Flyway
  12.4.0 is at test scope only, for the plain-Java example test.
- **Starter**: `org.flywaydb:flyway-core` and `org.flywaydb:flyway-database-postgresql`, both optional, with versions
  from Spring Boot 4.1's BOM (12.4.0). It uses `@DependsOnDatabaseInitialization` from `spring-boot-sql`, which is
  already a dependency.

**Storage**: Postgres.
- New: `saga_forge_schema_history` (Flyway's layout, created by `migrate`).
- New resources: `migration/V1..V5__*.sql` and `schema-levels/V1..V5.json`.
- Unchanged: Saga Forge tables, `schema.sql` and `schema-upgrade-NNN.sql`.

**Testing**: JUnit 5 and AssertJ.
- **Unit tests**: `SchemaComparisonTest` (pure), `MigrationResourcesTest`, `ReleasedMigrationsTest`, and the
  migrate-without-Flyway analyzer test with `FilteredClassLoader`.
- **Container tests** (`@Tag("container")`, `-Pcontainer-tests`, `postgres:16-alpine`): equivalence, snapshots,
  validator, detection, the starter integration (`ApplicationContextRunner`, G-008-04), concurrency, and plain Java.

**Target Platform**: JVM library (Spring Boot 4.1 for the starter)

**Project Type**: multi-module Maven library

**Performance Goals**: a start against an up-to-date database costs one history read and one catalog read (spec US1
scenario 3). The catalog read covers six tables, which is negligible.

**Constraints**:
- Constitution Principles I and IV: Flyway only in the starter, Postgres only.
- Released versions are immutable.
- The check never writes.

**Scale/Scope**: two modules change (storage, starter), plus docs. No core or runtime change, and no new module.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-checked after Phase 1 design.*

| Principle | Check | Result |
|-----------|-------|--------|
| I. Zero-Dependency Core | Core and runtime are untouched. Flyway is an optional dependency of the starter only. The storage module ships plain SQL and JSON and reads `pg_catalog` over JDBC. | Pass |
| II. Annotation-Driven, Declarative | No change to the saga model. Schema management is configured by properties, like the existing `initialize-schema`. | Pass (n/a) |
| III. Formal Spec Conformance | No annotation, scanner or lifecycle semantics change, so no Property or rule changes in `.kiro/specs/`. Followed through Speckit. | Pass |
| IV. Explicit, Narrow Scope | Postgres only. Liquibase, MySQL and MongoDB are out and recorded (G-018-01, G-018-05). Flyway is an integration in the starter, which the roadmap's genericity test allows. | Pass |
| V. Documentation Hierarchy | `docs/architecture.md` (storage section), the new `docs/guide/schema.md`, `getting-started.md` and `operations.md` mentions, the starter README, `SKILL.md`, `AGENTS-snippet.md`, `llms-full.txt`, `memory.md` and `AGENTS.md` are updated by tasks. | Pass (task) |
| Workflow: composition, tested abstractions, known vocabulary | See the table below. There is one sealed hierarchy (`SchemaDetection`: three value variants, designed as one), allowed by the workflow rule, and no class extends a concrete class. The one new interface (`SchemaMigrator`) has two implementations and a test against the double. | Pass |

Abstractions and how recognisable they are (Development Workflow):

| Abstraction | Shape | Known from | Rating |
|---|---|---|---|
| Versioned migrations `V<N>__name.sql`, history table, baseline | resources | Flyway, Liquibase, Rails and Django migrations | very common |
| `PostgresSchemaValidator` / `validate()` | class | Hibernate `SchemaValidator` and `hbm2ddl=validate`, Flyway `validate` | very common |
| `SchemaValidationReport` / `SchemaDifference` | records | schema-diff tools (Liquibase `diff`, migra) | common |
| `SchemaVersion`, `baselineVersion` | record, property | Flyway `MigrationVersion`, `baselineVersion` | very common |
| `SchemaDetection` (`Empty`/`Matched`/`Unmatched`) | sealed result | result types; "detect the schema version" as in Flyway's baseline-on-migrate | common |
| `SchemaMigrator` (`migrate(baseline)`) | package-private interface | Migrator in Rails, Django, Knex | common |
| `SchemaInitializer`, `initialize-schema` modes | existing | Spring Boot `spring.sql.init.mode`, Hibernate `ddl-auto` (`validate`) | very common |

AGENTS.md asks to propose the `effective-java`, `clean-code-java` and `solid-design-review` skills before class-level
design. They are proposed here for `/speckit-implement`. The data model already follows them:
- immutable records with validating constructors;
- severity derived from the kind, not a boolean parameter;
- a pure comparison function apart from the I/O;
- a final validator with no I/O in its constructor;
- no new public API in the starter beyond `SchemaStatus` and one failure analyzer.

**Post-design re-check: Pass.** Two deviations from the request were found during research and folded into the
spec:
- the resource location (R2);
- the spec's assumption that no version rewrites rows: 011 does (R1).

## Project Structure

### Documentation (this feature)

```text
specs/018-versioned-schema-migrations/
├── plan.md                 # this file
├── spec.md
├── research.md             # R1–R12, gaps G-018-01..06
├── data-model.md           # resources, public and package-private types, initialiser flow
├── quickstart.md           # validation scenarios
├── contracts/
│   ├── migration-resources.md
│   ├── schema-check.md
│   └── starter-schema-initialization.md
├── checklists/requirements.md
└── tasks.md                # /speckit-tasks
```

### Source Code (repository root)

```text
saga-forge-storage-postgres/
├── src/main/java/io/github/gkosharovdev/sagaforge/storage/postgres/
│   ├── PostgresSchema.java              # new: location, history table, versions
│   ├── SchemaVersion.java               # new
│   ├── PostgresSchemaValidator.java     # new: validate, detectVersion, appliedVersion
│   ├── SchemaValidationReport.java      # new
│   ├── SchemaDifference.java            # new
│   ├── SchemaDetection.java             # new (sealed)
│   ├── SagaSchemaException.java         # new
│   ├── SchemaSnapshot.java              # new, package-private
│   ├── CatalogReader.java               # new, package-private
│   ├── SchemaComparison.java            # new, package-private
│   └── Definitions.java                 # new, package-private
├── src/main/resources/io/github/gkosharovdev/sagaforge/storage/postgres/
│   ├── migration/V1__base_schema.sql … V5__saga_operations.sql   # new
│   ├── schema-levels/V1.json … V5.json                         # new
│   ├── schema.sql, schema-upgrade-*.sql                        # unchanged
└── src/test/
    ├── java/.../MigrationEquivalenceTest, SchemaSnapshotTest, PostgresSchemaValidatorTest,
    │        SchemaDetectionTest, SchemaComparisonTest, MigrationResourcesTest,
    │        ReleasedMigrationsTest, PlainJavaMigrationExampleTest
    └── resources/.../released/schema-0.1.0.sql, schema-0.2.0.sql, schema-0.4.0.sql,
                      released-migrations.sha256

saga-forge-spring-boot-starter/
├── pom.xml                                          # + optional flyway-core, flyway-database-postgresql
├── src/main/java/io/github/gkosharovdev/sagaforge/spring/
│   ├── SagaForgeProperties.java                     # VALIDATE, MIGRATE; Postgres.Migration
│   ├── storage/SchemaInitializer.java               # modes, status()
│   ├── storage/SchemaMigrator.java                  # new, package-private
│   ├── storage/FlywaySchemaMigrator.java            # new, package-private
│   ├── storage/SchemaStatus.java                    # new
│   ├── storage/PostgresStorageConfiguration.java    # @DependsOnDatabaseInitialization; nested Flyway config
│   ├── diagnostics/SagaSchemaFailureAnalyzer.java   # new
│   └── actuator/SagaForgeEndpoint.java              # schema object
├── src/main/resources/META-INF/spring.factories     # + analyzer
├── src/main/resources/META-INF/additional-spring-configuration-metadata.json
├── README.md                                        # properties table, gotcha rewritten
└── src/test/java/.../SchemaInitializerTest, FlywaySchemaMigrationIntegrationTest,
                     ConcurrentMigrationTest, MigrateWithoutFlywayTest, SagaSchemaFailureAnalyzerTest

docs/guide/schema.md (new), mkdocs.yml, docs/guide/build-llms-full.sh, llms-full.txt,
docs/guide/getting-started.md, docs/guide/operations.md, docs/architecture.md,
docs/agents/saga-forge/SKILL.md, docs/agents/AGENTS-snippet.md, .specify/memory/memory.md, AGENTS.md
```

**Structure Decision**: The feature lives in the two existing modules that own schema concerns today: the storage
module (resources and the check) and the starter (modes and Flyway). No module is added. A separate
`saga-forge-flyway` module was considered and rejected: the starter is the only Flyway user, and plain-Java hosts drive
their own Flyway with the shipped constants (R12).

## Implementation Order

1. **Migration resources, equivalence and freeze** (US1, US2 foundation): the V1..V5 files, `released/` copies,
   `MigrationEquivalenceTest`, `MigrationResourcesTest` and `ReleasedMigrationsTest`.
2. **Snapshot, catalog reader and comparison** (US3): `SchemaComparison` unit tests first, then `CatalogReader`, the
   snapshots and `SchemaSnapshotTest`.
3. **Validator and detection** (US3, US2): `PostgresSchemaValidator`, the reports, `SchemaDetectionTest`.
4. **Starter modes** (US1, US2, US3): properties, `SchemaInitializer` with the `SchemaMigrator` double,
   `FlywaySchemaMigrator`, the ordering, the failure analyzers, the endpoint, and the container tests including
   concurrency.
5. **Plain Java** (US4): `PlainJavaMigrationExampleTest`, the source of the guide's snippet.
6. **Docs and register**: the guide page, READMEs, agent skill and snippet, `llms-full.txt`, `memory.md` (close
   G-003-25, add G-018-01..06, a release-checklist line for checksums), `AGENTS.md`. Then the full
   `mvn -Pcontainer-tests verify` and the release build.

## Complexity Tracking

No constitution violation to justify.
