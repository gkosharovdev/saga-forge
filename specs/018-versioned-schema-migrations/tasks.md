---

description: "Task list for 018-versioned-schema-migrations"
---

# Tasks: Versioned Schema Migrations

**Input**: Design documents from `/specs/018-versioned-schema-migrations/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md),
[contracts/](contracts/), [quickstart.md](quickstart.md)

**Tests**: included. Every story has an independent test, the quickstart and the contracts name the suites, and
AGENTS.md asks for a test with every new abstraction, written with its first caller.

**Organization**: the tasks run in this order.
1. Setup.
2. The foundational phase:
   - the migration files and their equivalence and freeze tests;
   - the schema versions;
   - the snapshot, catalog reader and comparison;
   - the validator.

   `migrate`, takeover and `validate` all need these.
3. The stories in spec order:
   - US1, the starter's `migrate` on an empty or already migrated database;
   - US2, taking over an existing database (detection and baseline);
   - US3, the starter's `validate` and reporting;
   - US4, plain Java.
4. Docs and register, then verification.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an unfinished task)
- **[Story]**: the user story from spec.md (US1 to US4)

## Path Conventions

These abbreviations stand for the following paths:

| Abbreviation | Path |
|---|---|
| `pg…` | `saga-forge-storage-postgres/src/main/java/io/github/gkosharovdev/sagaforge/storage/postgres` |
| `pg-res…` | `saga-forge-storage-postgres/src/main/resources/io/github/gkosharovdev/sagaforge/storage/postgres` |
| `pg-test…` | `saga-forge-storage-postgres/src/test/java/io/github/gkosharovdev/sagaforge/storage/postgres` |
| `pg-test-res…` | `saga-forge-storage-postgres/src/test/resources/io/github/gkosharovdev/sagaforge/storage/postgres` |
| `st…` | `saga-forge-spring-boot-starter/src/main/java/io/github/gkosharovdev/sagaforge/spring` |
| `st-test…` | `saga-forge-spring-boot-starter/src/test/java/io/github/gkosharovdev/sagaforge/spring` |
| `st-test-res` | `saga-forge-spring-boot-starter/src/test/resources` |

Container tests carry `@Tag("container")` and use `postgres:16-alpine`, like the existing suites.

---

## Phase 1: Setup

**Purpose**: a green baseline, the dependencies and the design skills.

- [X] T001 On branch `018-versioned-schema-migrations` (created from the 016 commit; rebase onto `master` once 016 is merged), run `mvn -q install` from the repository root and confirm it is green before any change
- [X] T002 Load the `effective-java`, `clean-code-java` and `solid-design-review` skills (AGENTS.md) and keep them applied to every type added below; read `specs/018-versioned-schema-migrations/research.md` R1–R12 and the three files in `specs/018-versioned-schema-migrations/contracts/`
- [X] T003 [P] Add `org.flywaydb:flyway-core` and `org.flywaydb:flyway-database-postgresql` to `saga-forge-spring-boot-starter/pom.xml` as `<optional>true</optional>` (versions from Spring Boot's BOM, 12.4.0), and to `saga-forge-storage-postgres/pom.xml` at `<scope>test</scope>` only; run `mvn -q -pl saga-forge-storage-postgres dependency:tree` and confirm Flyway does not appear at compile or runtime scope (FR-004)

---

## Phase 2: Foundational (blocking prerequisites)

**Purpose**: the versions, proven equal to the scripts and frozen, and the read-only schema check that every story uses.

### Migration resources (contracts/migration-resources.md, research R1–R4)

- [X] T004 Create `pg-res…/migration/V1__base_schema.sql` with the statements of `schema.sql` as tagged `v0.1.0` (`git show v0.1.0:saga-forge-storage-postgres/src/main/resources/io/github/gkosharovdev/sagaforge/storage/postgres/schema.sql`). Create `V2__saga_outcome_events.sql` (from `schema-upgrade-011.sql`), `V3__saga_deadlines.sql` (017), `V4__active_standby_relay.sql` (013) and `V5__saga_operations.sql` (015) from the current upgrade scripts. Change only the header comments, so they say "Saga Forge schema version N, applied by Flyway under its own history; see docs/guide/schema.md"; keep every statement as it is
- [X] T005 [P] Copy the released `schema.sql` files into `pg-test-res…/released/schema-0.1.0.sql`, `schema-0.2.0.sql` and `schema-0.4.0.sql` (`git show v0.1.0:…`, `v0.2.0:…`, `v0.4.0:…`)
- [X] T006 [P] Add `pg-test…/MigrationResourcesTest.java` (unit). It lists the classpath directory `migration/` and checks:
  - the names match `V<N>__[a-z_]+\.sql`, contiguous from 1;
  - no file contains `${`, `CONCURRENTLY` or `VACUUM`;
  - no statement names a schema (`public.` or `"public"`).
- [X] T007 [P] Add `pg-test-res…/released-migrations.sha256` with the SHA-256 of V1..V4. V5 is added at the next release (T051). Add `pg-test…/ReleasedMigrationsTest.java` (unit). It fails when a listed file's hash differs, naming the file and saying "released schema versions are frozen; add a new version instead". It also asserts that every file not listed is newer than the newest listed one, so only unreleased versions can be missing from the list

### Versions

- [X] T008 [P] Add `pg…/SchemaVersion.java`, a record with `number` (≥ 1) and `description` (non-blank), `Comparable` by number. It has `toString()` → `"V5 (saga operations)"`, and `static of(int)`, which throws `IllegalArgumentException` naming the known range for an unknown number. Add `pg…/PostgresSchema.java` (final, private constructor) with:
  - `MIGRATION_LOCATION`, `DEFAULT_HISTORY_TABLE = "saga_forge_schema_history"` and `SCHEMA_SCRIPT` (the `schema.sql` classpath path);
  - `currentVersion()`, and `versions()`, parsed from the `migration/` file names at class initialisation (descriptions: underscores become spaces). *Done as a constant list in `PostgresSchema`, with `MigrationResourcesTest` asserting that it equals the file names: listing a class path directory inside a jar is not reliable.*

  Tests are in `pg-test…/SchemaVersionTest.java`: parsing, ordering, `of` with known and unknown numbers, and `toString`

### Snapshot, catalog and comparison (contracts/schema-check.md, research R5)

- [X] T009 [P] Add `pg…/SchemaDifference.java`, a record with nested enums:
  - `ObjectKind` (`TABLE`, `COLUMN`, `CONSTRAINT`, `INDEX`);
  - `Problem` (`MISSING`, `DIFFERENT`);
  - `Severity` (`FAILURE`, `WARNING`).

  The constructor derives the severity from the kind (`INDEX` → `WARNING`), requires `found == null` iff `MISSING`, and has no boolean parameter. Also add `pg…/SchemaValidationReport.java`, a record (`expected`, `differences` as an immutable copy sorted by severity, table, kind, name) with `passed()`, `failures()`, `warnings()` and `describe()` in the exact format of contracts/schema-check.md. Unit tests are in `pg-test…/SchemaValidationReportTest.java`
- [X] T010 [P] Add package-private `pg…/Definitions.java`. It normalises check and index definitions: collapse whitespace, strip the index name and the `ON <schema>.` qualifier from `pg_get_indexdef` output, and strip `public.` qualifiers. Unit tests in `pg-test…/DefinitionsTest.java` use real `pg_get_constraintdef`/`pg_get_indexdef` strings from the current schema
- [X] T011 Add package-private `pg…/SchemaSnapshot.java`, an immutable value with `tables` → `columns` (name, type, notNull), `constraints` (kind `PRIMARY_KEY`/`UNIQUE`/`FOREIGN_KEY`/`CHECK`, columns, references, referencedColumns, onDelete, definition) and `indexes` (definition), as in data-model.md. It has:
  - `static SchemaSnapshot load(SchemaVersion)`, which reads `schema-levels/V<N>.json` with the module's Jackson 3 `JsonMapper`;
  - `String toJson()`, which writes stable, sorted, pretty JSON.

  Round-trip tests are in `pg-test…/SchemaSnapshotJsonTest.java`
- [X] T012 Add package-private `pg…/SchemaComparison.java`, with `static List<SchemaDifference> compare(SchemaSnapshot expected, SchemaSnapshot found)`, a pure function. It matches by meaning (contracts/schema-check.md table): tables and columns by name; primary key and unique by column set; foreign key by columns, referenced table and columns (on-delete compared as shape); checks and indexes by normalised definition. It ignores extras and reports `MISSING`/`DIFFERENT`. Write `pg-test…/SchemaComparisonTest.java` (unit) first, with one case per table row:
  - a missing table;
  - a missing column, a wrong type and a wrong nullability;
  - a missing primary key or unique;
  - a foreign key with a different on-delete;
  - a check renamed but equal (no difference), and a check different (missing);
  - a missing index (warning);
  - an extra table, column, constraint or index (nothing reported);
  - the ordering.
- [X] T013 Add package-private `pg…/CatalogReader.java`, with `SchemaSnapshot read(Connection, Set<String> tableNames)`. It reads `pg_catalog` for `current_schema()` only: `pg_attribute` with `format_type` and `attnotnull` (no dropped or system columns); `pg_constraint` with `conkey`/`confkey` mapped to column names, the referenced table, `confdeltype` mapped to `NO ACTION`/`RESTRICT`/`CASCADE`/`SET NULL`/`SET DEFAULT`, and `pg_get_constraintdef` for checks; and `pg_index` with `pg_get_indexdef`, excluding indexes that back a primary key or unique constraint. All definitions pass through `Definitions`
- [X] T014 Generate `pg-res…/schema-levels/V1.json` … `V5.json` with a new container test `pg-test…/SchemaSnapshotTest.java`. For each version N, it migrates a fresh schema with Flyway (test scope; `PostgresSchema.MIGRATION_LOCATION`, `placeholderReplacement(false)`) to `target(N)`, reads it with `CatalogReader` for the union of all versions' table names, and asserts the result equals `SchemaSnapshot.load(N)`. With `-Dsaga-forge.write-snapshots=true`, it writes the files into `src/main/resources` instead. Commit the generated files, and check by hand that V1 contains `saga_message_store_consumer` and no `saga_message_store_leader`, and that V5 contains `resolution`, `compensation_round` and `open_deadlines`
- [X] T015 Add container test `pg-test…/MigrationEquivalenceTest.java` (FR-003, SC-003). It compares `CatalogReader` output, plus column defaults read from `pg_attrdef` for this test only, across three schemas:
  - V1..V5 migrated;
  - the current `schema.sql` applied;
  - for each of `released/schema-0.1.0.sql`, `schema-0.2.0.sql` and `schema-0.4.0.sql`, that file followed by the later upgrade scripts in commit order (011, 017, 013, 015; skipping those already contained).

  All of them must be equal

### Validator

- [X] T016 Add `pg…/SchemaDetection.java`, a sealed interface with records `Empty`, `Matched(SchemaVersion version, SchemaValidationReport report)` and `Unmatched(SchemaValidationReport closest)`. Add `pg…/SagaSchemaException.java` (extends `RuntimeException`, carries the report, message = `report.describe()`). *Done with factories `failedCheck(report)` and `unmatched(Unmatched)` and an `unmatched()` flag, instead of a nullable `SchemaDetection` (T033).* Add `pg…/PostgresSchemaValidator.java` (final; the constructor takes a `DataSource` and does no I/O). Each method opens one read-only connection and wraps `SQLException` in `UncheckedSqlException`:
  - `validate()` and `validate(SchemaVersion)`;
  - `detectVersion()` per research R6: `Empty` when none of the newest snapshot's tables exist; otherwise newest first; `Unmatched` with the fewest failures, ties going to the newer version;
  - `appliedVersion(String historyTable)`: plain SQL over Flyway's history layout, newest `success = true` row with a numeric `version`, ignoring the `BASELINE` type for "applied" but returning it when it is the only row; empty when the table is missing.
- [X] T017 Add container test `pg-test…/PostgresSchemaValidatorTest.java` (FR-011 to FR-014, SC-004). It covers:
  - a migrated schema passes with no difference;
  - one parameterised case per Saga Forge table drops one non-key column and asserts a single `FAILURE` naming that table and column;
  - a wrong type, a dropped check and a dropped index give two failures and one warning, and `describe()` matches the contract's format;
  - an extra column, table and index give no difference;
  - after `validate()`, `pg_stat_user_tables` write counters and the catalog are unchanged.
- [X] T018 Add container test `pg-test…/SchemaDetectionTest.java`. It covers:
  - an empty schema → `Empty`;
  - each released `schema.sql` → V1, V2 and V4 respectively;
  - V1..V3 migrated → V3;
  - a 0.1.0 schema whose inline check constraints were renamed → still V1;
  - a 0.4.0 schema with one column dropped → `Unmatched`, whose `closest` is V4 and names the column;
  - a schema with an extra application table → its level;
  - `appliedVersion` for no table, a baseline-only history and a migrated history.

**Checkpoint**: `mvn -Pcontainer-tests -pl saga-forge-storage-postgres verify` is green; the storage module has no Flyway at compile scope.

---

## Phase 3: User Story 1 - An application on Flyway gets Saga Forge's schema applied for it (Priority: P1) 🎯 MVP

**Goal**: `initialize-schema=migrate` applies Saga Forge's versions under its own history, after the application's own
migrations, on an empty or already migrated database.

**Independent Test**: an application with its own Flyway V1..V62 starts against an empty database. Both histories are
complete, and a saga completes (SC-001).

- [X] T019 [US1] In `st…/SagaForgeProperties.java`:
  - add `VALIDATE` and `MIGRATE` to `SchemaInitialization`, with Javadoc per value from contracts/starter-schema-initialization.md;
  - add nested `Postgres.Migration` with `historyTable` (default `PostgresSchema.DEFAULT_HISTORY_TABLE`) and `baselineVersion` (`Integer`, nullable), exposed as `getMigration()`;
  - add the three entries to `saga-forge-spring-boot-starter/src/main/resources/META-INF/additional-spring-configuration-metadata.json`, with value hints for `initialize-schema`.
- [X] T020 [US1] Add package-private `st…/storage/SchemaMigrator.java`, an interface with `SchemaVersion migrate(SchemaVersion baselineOrNull)` that returns the newest applied version. Add package-private `st…/storage/FlywaySchemaMigrator.java` (final; takes the `DataSource`, the history table and the class loader) per research R7:
  - it uses `Flyway.configure(classLoader)` with `locations(PostgresSchema.MIGRATION_LOCATION)`, `table(...)` and `placeholderReplacement(false)`;
  - it sets `baselineOnMigrate(true)` and `baselineVersion(String.valueOf(n))` only when a baseline is given;
  - it logs each applied version at INFO through `System.Logger` or SLF4J, as the starter does elsewhere;
  - before migrating, it checks `ClassUtils.isPresent("org.flywaydb.database.postgresql.PostgreSQLDatabaseType", classLoader)` and throws `SagaForgeConfigurationException` naming `org.flywaydb:flyway-database-postgresql` if absent.
  - *Done, with a deviation*: `baselineOnMigrate(true)` is always set, with `baselineVersion` `0` when no baseline is given. Flyway refuses a schema that holds the application's own tables but no history, and a Boot application's schema always does; a `0` baseline lets V1 onwards apply. In a truly empty schema Flyway writes no baseline row (G-018-07).
- [X] T021 [US1] Rework `st…/storage/SchemaInitializer.java` per data-model.md's flow, keeping it public and keeping `SchemaInitializer.SCHEMA` (now delegating to `PostgresSchema.SCHEMA_SCRIPT`):
  - the constructor becomes `(DataSource, SchemaInitialization, PostgresSchemaValidator, SchemaMigrator migratorOrNull, Migration)`;
  - `MIGRATE` without a migrator throws `SagaForgeConfigurationException` with the text in contracts/starter-schema-initialization.md;
  - `MIGRATE` applies `appliedVersion` → baseline → `migrate` → `validate`;
  - `NEVER` and `ALWAYS` keep today's checks, then run `validate()` for status only and log failures and warnings at WARN;
  - add `status()` returning a new public record `st…/storage/SchemaStatus.java` (`mode`, `version` nullable, `report` nullable).
  - *Done, with a deviation*: the constructor takes a sixth argument, the application's `ClassLoader`, so the missing-Flyway text names the artifacts the application lacks (a `FilteredClassLoader` in `MigrateWithoutFlywayTest`).

  US2 and US3 complete the baseline and validate branches. Here, `MIGRATE` passes `null` as baseline when the history table is missing and the database is `Empty`.
- [X] T022 [US1] In `st…/storage/PostgresStorageConfiguration.java`:
  - annotate the `sagaForgeSchemaInitializer` bean method with `@DependsOnDatabaseInitialization` (`org.springframework.boot.sql.init.dependency`);
  - give it a `PostgresSchemaValidator` and an `ObjectProvider<SchemaMigrator>`;
  - add a nested static `@Configuration(proxyBeanMethods = false)` class with `@ConditionalOnClass(name = "org.flywaydb.core.Flyway")`, declaring the `FlywaySchemaMigrator` bean (`@ConditionalOnMissingBean(SchemaMigrator.class)`).
  - *Done, with a deviation*: the starter declares `spring-boot-sql` as an optional dependency (it was managed, not declared); the nested configuration is conditional on both Flyway classes. The starter's test `application.properties` sets `spring.flyway.enabled=false`, because `spring-boot-flyway` on the test class path would otherwise run Boot's Flyway in every test application.

  The storage beans keep their `SchemaInitializer` parameter.
- [X] T023 [US1] Add container test `st-test…/storage/SchemaInitializerTest.java` with a recording `SchemaMigrator` double that delegates to a real `FlywaySchemaMigrator`. It covers:
  - `MIGRATE` on an empty database calls `migrate(null)` once and ends `PASSED`;
  - the second initialisation finds the history, migrates nothing new, and makes one history read and one catalog read;
  - `MIGRATE` with a null migrator throws, naming both artifacts.
- [X] T024 [US1] Add container test `st-test…/storage/FlywaySchemaMigrationIntegrationTest.java` (`ApplicationContextRunner` with Spring Boot's Flyway auto-configuration, G-008-04). Test resources are `st-test-res/db/migration/app/V1__…sql` … `V62__…sql`, generated as trivial `CREATE TABLE app_tN (id int)` files by a small helper in the test, or checked in. It covers:
  - an empty database with `spring.flyway.locations=classpath:db/migration/app` and `initialize-schema=migrate` → `flyway_schema_history` lists 1..62 and `saga_forge_schema_history` lists 1..5; the canonical saga from `saga-forge-example-spring`'s test configuration, or the starter's existing test saga, completes (SC-001);
  - a restart applies nothing;
  - with `spring.flyway.enabled=false`, Saga Forge still migrates;
  - the order holds: an application migration recorded in a test table runs before Saga Forge's history table exists.
- [X] T025 [US1] Add container test `st-test…/storage/ConcurrentMigrationTest.java` (FR-010, SC-005). Three `SpringApplicationBuilder` contexts start concurrently from a `CountDownLatch` against one empty database, and again against one V1 database. Assert that each version appears exactly once in `saga_forge_schema_history`, that all three contexts start, and that the schema validates
- [X] T026 [US1] Add `st-test…/storage/MigrateWithoutFlywayTest.java` (unit, `ApplicationContextRunner` with `FilteredClassLoader(Flyway.class)` and, separately, a filter hiding `PostgreSQLDatabaseType`). Startup fails, and the analysed failure (`SagaForgeConfigurationFailureAnalyzer`) names the property, the value and the missing artifact or artifacts

**Checkpoint**: US1 works on empty and up-to-date databases; `mvn -Pcontainer-tests -pl saga-forge-spring-boot-starter verify` is green.

---

## Phase 4: User Story 2 - An existing database is taken over without loss (Priority: P1)

**Goal**: a database with Saga Forge's tables and no Saga Forge history is recognised, baselined and brought forward,
with every row kept.

**Independent Test**: a 0.1.0-level database with rows in every table starts with `migrate`. The baseline is V1, V2 to
V5 are applied, the rows are kept, and a saga started before the switch completes after it (SC-002).

- [X] T027 [US2] Complete the `MIGRATE` baseline branch in `st…/storage/SchemaInitializer.java`:
  - no history and `baselineVersion` set → `SchemaVersion.of(it)` (an unknown number throws `SagaForgeConfigurationException` naming the range), without detection;
  - no history and no property → `detectVersion()`: `Empty` → null; `Matched` → its version; `Unmatched` → throw `SagaSchemaException(closest)` before any write;
  - history present → baseline null.

  Log the baseline at INFO ("Saga Forge schema baselined at V1 (base schema), detected").
- [X] T028 [P] [US2] Add test fixtures `st-test-res/takeover/rows-0.1.0.sql`, `rows-0.2.0.sql` and `rows-0.4.0.sql`, inserting at least one row into every Saga Forge table valid for that level. The 0.1.0 and 0.2.0 fixtures include a `REQUIRES_INTERVENTION` instance and history entry, so V2's data rewrite is exercised
  - *Done*: `takeover/rows-common.sql` plus one file per level; the released `schema-<v>.sql` files are copied into `takeover/` (the storage module has no test-jar). Only 0.1.0 holds `REQUIRES_INTERVENTION`: the 0.2.0 check already forbids it.
- [X] T029 [US2] Extend `st-test…/storage/FlywaySchemaMigrationIntegrationTest.java` with takeover cases (FR-007, FR-009, SC-002). For each released level, apply `released/schema-<v>.sql` (from the storage test-jar or copied into the starter's test resources) and its rows, then start with `migrate`, and assert:
  - the baseline row is V1, V2 or V4;
  - the later versions are applied;
  - the row counts are unchanged, except the deletions V2 makes, which are asserted exactly;
  - the instance is renamed to `COMPENSATION_FAILED`.

  Also cover:
  - a `RUNNING` instance created before the switch completes after it through the started runtime;
  - an application whose own `V62` contains `released/schema-0.1.0.sql` keeps `flyway_schema_history` unchanged and gets V1 as baseline;
  - a configured `baseline-version=1` skips detection: assert with a database whose check was renamed and differs, which would otherwise be `Unmatched`.
- [X] T030 [US2] Extend `st-test…/storage/SchemaInitializerTest.java`: an `Unmatched` database (one column dropped at 0.4.0 level) fails startup with `SagaSchemaException`, the database and both histories are unchanged (no history table created), and the message names the closest version and the column (FR-008)
- [X] T031 [US2] Extend `st-test…/storage/ConcurrentMigrationTest.java`'s V1 case to start from `released/schema-0.1.0.sql` with rows. Exactly one baseline row is written and every row is kept

**Checkpoint**: US1 and US2 together remove the copied schema and its drift guard for new and existing databases.

---

## Phase 5: User Story 3 - Startup refuses a database the library cannot use (Priority: P2)

**Goal**: `initialize-schema=validate` fails startup on any missing or different table, column or constraint, and lists
every difference. Index differences warn.

**Independent Test**: one column dropped from a migrated database fails `validate`, and the message names the table
and the column.

- [X] T032 [US3] Complete the `VALIDATE` branch in `st…/storage/SchemaInitializer.java`: `validate()`; not `passed()` → throw `SagaSchemaException`; warnings are logged at WARN, one line each; nothing is written (FR-014, FR-015)
- [X] T033 [P] [US3] Add `st…/diagnostics/SagaSchemaFailureAnalyzer.java` (`AbstractFailureAnalyzer<SagaSchemaException>`), with the description and action texts from contracts/starter-schema-initialization.md. The "Unmatched" variant is recognised by the report's expected version being the closest level; let `SagaSchemaException` carry a nullable `SchemaDetection` to tell them apart. Register it in `saga-forge-spring-boot-starter/src/main/resources/META-INF/spring.factories`. Unit tests are in `st-test…/diagnostics/SagaSchemaFailureAnalyzerTest.java` for both texts
  - *Done, with a deviation*: the analyzer implements `FailureAnalyzer` and touches `SagaSchemaException` only behind a class-presence check, since the Postgres module is optional in the starter. `SagaSchemaException.unmatched()` (added in T016) tells the two texts apart.
- [X] T034 [US3] Add container test `st-test…/storage/SchemaValidateModeTest.java` (`ApplicationContextRunner`). It covers:
  - a migrated database starts with `validate`, and no history table is created;
  - a dropped column fails with the analyzer's text naming table, column and expected type;
  - a wrong type, a dropped check and an extra column list the first two only;
  - a dropped index starts and logs one WARN naming the index;
  - `never` with a dropped column starts and logs it at WARN.
  - *Done, with a deviation*: warnings are asserted on `SchemaInitializer.status().report()`, whose `describe()` is the logged text. Spring Boot's `OutputCaptureExtension` fails with this JUnit version (`NoSuchMethodError` on `ExtensionContext.Store.computeIfAbsent`).
- [X] T035 [US3] In `st…/actuator/SagaForgeEndpoint.java` and `st…/actuator/SagaForgeActuatorConfiguration.java`, add the `schema` object (`mode`, `version` as text, `check` `PASSED`/`FAILED`, `failures`, `warnings`) from `SchemaInitializer.status()` when a `SchemaInitializer` bean exists. Omit it when there isn't one. Extend the existing endpoint test in `st-test…/actuator/` (FR-020)
  - *Done*: `SchemaStatus.view()` builds the object; the actuator configuration reads the initializer through the bean factory, behind a class-presence check for the Postgres module.

**Checkpoint**: all four modes behave as in contracts/starter-schema-initialization.md.

---

## Phase 6: User Story 4 - Hosts without the starter use the same migrations and check (Priority: P3)

**Goal**: a plain-Java host runs its own Flyway on Saga Forge's location and table, and runs the check, with no Spring.

**Independent Test**: without Spring, migrate an empty database with Flyway's API using `PostgresSchema` constants,
then validate. It passes, and it fails with a dropped column.

- [X] T036 [US4] Add container test `pg-test…/PlainJavaMigrationExampleTest.java` (Flyway at test scope). Mark the guide snippet's region with `// tag::migrate[]` … `// end::migrate[]` comments: detect when there is no history (`appliedVersion(DEFAULT_HISTORY_TABLE).isEmpty()`), then `Flyway.configure()...baselineVersion(...)` per contracts/migration-resources.md, `migrate()`, `validate()`, and throw `SagaSchemaException` when not passed. Assert:
  - an empty database ends equal to `schema.sql`, and its report passes;
  - a 0.1.0 database is baselined at V1;
  - a dropped column gives the same `describe()` text as the starter's `validate` test.

**Checkpoint**: every story is independently green.

---

## Phase 7: Documentation and register

**Purpose**: consumer docs, agent docs and the gap register (FR-021, FR-022; Constitution Principle V).

- [X] T037 [P] Add `docs/guide/schema.md`, with no spec numbers or gap ids (guide rule). It covers:
  - the four modes and when to use each;
  - `migrate` with the application's own Flyway (the two histories, the order, the dependencies to add);
  - taking over an existing database (detection, `baseline-version`, what `Unmatched` means and how to fix it);
  - `validate` for Liquibase or DBA-managed schemas;
  - plain Java, with the snippet copied from `PlainJavaMigrationExampleTest`, naming that file;
  - "never copy Saga Forge's migrations into your own location";
  - rolling deploys: each release notes whether its version keeps the previous release working.
- [X] T038 [P] Register the page: add it to `mkdocs.yml`'s navigation after `operations`, to `docs/guide/README.md`'s index, and to the page list in `docs/guide/build-llms-full.sh`. Then run `docs/guide/build-llms-full.sh` and commit `llms-full.txt`
- [X] T039 [P] Update `docs/guide/getting-started.md`, where the schema is set up, to show `initialize-schema=migrate` with the two Flyway dependencies as the production path, and `always` as the development shortcut. Update `docs/guide/operations.md` with a short "Schema version" paragraph about the `sagaforge` endpoint's `schema` object, linking to `schema.md`. Check `docs/guide/deadlines.md` and `docs/guide/validation-rules.md`, which mention `schema-upgrade`/`initialize-schema` today, and reword them to point to `schema.md`
- [X] T040 [P] Update `saga-forge-spring-boot-starter/README.md`:
  - the properties table: four values for `initialize-schema`, plus `migration.history-table` and `migration.baseline-version`;
  - the "Switches" example;
  - rewrite the gotcha "`initialize-schema=always` is for development" to recommend `migrate` or `validate` for production;
  - an "Optional dependencies" note for Flyway.

  Run `ReadmePropertiesTest`.
- [X] T041 [P] Add or extend `saga-forge-storage-postgres/README.md` (if it exists; otherwise update `pg…/package-info.java`'s Javadoc) on the migration location, the history table, `PostgresSchemaValidator`, and that `schema.sql` and the upgrade scripts remain for hosts without Flyway
- [X] T042 [P] Update `docs/architecture.md`'s storage section: schema versions, own history, the check by meaning, and the starter modes. Update the root `README.md` if it shows schema setup (it mentions `initialize-schema` today)
- [X] T043 [P] Update `docs/agents/saga-forge/SKILL.md` and `docs/agents/AGENTS-snippet.md`: the production schema rule (`migrate` or `validate`; never copy the migrations), and the failure texts an agent may see at startup
- [X] T044 Update `.specify/memory/memory.md`:
  - close G-003-25 ("closed by 018");
  - add G-018-01 to G-018-06 from research.md;
  - mark roadmap item 8 done in "Adoption backlog";
  - add to DEC-REL-01's release steps: "add new `migration/V*.sql` checksums to `released-migrations.sha256`".

  Update `.specify/memory/roadmap.md`'s feature-order row for 018 to done.
  - *Done*: also G-018-07 (the baseline row at 0) and G-018-08 (`OutputCaptureExtension`), found during implementation. `docs/guide/limitations.md` row 8 now names G-018-01.
- [X] T045 Update `AGENTS.md`: a "Current Status" bullet for 018 (what ships where, the location, the history table, the modes, the check, the versions V1..V5 in commit order, and the frozen released versions), the Module Map lines for `saga-forge-storage-postgres` and the starter, and the "Known Gaps" paragraph for G-018-01 to G-018-06. Add an "Agent Working Rules" line: "A schema change adds `migration/V<N+1>__*.sql`, updates `schema.sql` and an upgrade script, and regenerates `schema-levels/V<N+1>.json`; never edit a released version."

---

## Phase 8: Verification

- [X] T046 Run `mvn spotless:apply`, then `mvn verify` from the repository root; fix any failure
- [X] T047 Run `mvn -Pcontainer-tests verify` from the repository root and record the test count and result in `AGENTS.md`'s status list, as the earlier features did
  - *Done*: 2026-11-27, 2,317 tests, 0 failures, 6 skipped. `ConcurrentMigrationTest` sets `org.springframework.boot.logging.LoggingSystem=none` for its run: concurrent `SpringApplication` starts race on Boot's logging initialisation.
- [X] T048 Run the release build `mvn -P release -Dgpg.skip -DskipTests install` and fix any Javadoc error: no `<h3>` in type comments, no `@throws` on record comments
- [ ] T049 Walk through `specs/018-versioned-schema-migrations/quickstart.md` §3 by hand against `saga-forge-example-spring` with a local Postgres, and record the output in the PR description
  - *Not done here*: needs a local Postgres and the PR description; left for the PR.
- [X] T050 Re-check every FR-001 to FR-022 and SC-001 to SC-005 against a task and a passing test; list SC-006 (adopter confirmation) as open in the PR (SC-006 withdrawn 2026-11-27: booking-service never adopted Saga Forge)
  - *Done*: every FR and SC-001 to SC-005 has a passing test. FR-018 needed one more, `StartWithoutFlywayTest` (Flyway hidden by a `FilteredClassLoader`; `never`, `always` and `validate` start). SC-006 is withdrawn.
- [ ] T051 At the release that ships this feature (not in this PR): add the SHA-256 of V5 and of every later unreleased version to `pg-test-res…/released-migrations.sha256`

---

## Dependencies & Execution Order

### Phase dependencies

1. Setup (T001–T003).
2. Foundational (T004–T018), which blocks every story:
   - T004 comes before T006, T007, T008, T014 and T015;
   - T009 to T011 come before T012;
   - T012 and T013 come before T014;
   - T014 comes before T016 to T018.
3. The stories:
   - **US1** (T019–T026) depends on Foundational;
   - **US2** (T027–T031) depends on US1's `SchemaInitializer` rework (T021) and wiring (T022);
   - **US3** (T032–T035) depends on T021 and T022, and is independent of US2;
   - **US4** (T036) depends on Foundational only.
4. Docs (T037–T045) after the stories they describe.
5. Verification (T046–T050) last. T051 runs at release.

### Parallel opportunities

- **Setup and foundational**:
  - T003, T005, T006 and T007 in parallel after T004;
  - T008, T009 and T010 in parallel;
  - T017 and T018 in parallel after T016.
- **US3 and US2**: they can be built in parallel after T022. US3 touches the `VALIDATE` branch, the analyzer and the endpoint; US2 touches the `MIGRATE` baseline branch, the fixtures and the takeover cases. T027 and T032 edit the same file, `SchemaInitializer.java`, so coordinate them or do them one after the other.
- **US4 (T036)**: in parallel with any starter story.
- **Docs**: T037 to T043 in parallel (different files); T044 and T045 after them.

## Parallel Example: Foundational

```text
T008 SchemaVersion + PostgresSchema      (pg…/SchemaVersion.java, PostgresSchema.java)
T009 SchemaDifference + Report           (pg…/SchemaDifference.java, SchemaValidationReport.java)
T010 Definitions                         (pg…/Definitions.java)
```

## Parallel Example: after T022

```text
US2: T027 baseline branch → T029 takeover cases
US3: T033 failure analyzer, T035 endpoint schema object
US4: T036 plain-Java example test
```

## Implementation Strategy

### MVP (User Story 1)

Phases 1 and 2, then Phase 3. The result migrates empty and up-to-date databases under Saga Forge's own history. This
is enough for any new adopter, but not yet for a host whose tables already exist.

### Incremental delivery

1. MVP (US1).
2. US2, which lets a host with existing tables switch.
3. US3 gives `validate` for non-Flyway hosts and the endpoint status.
4. US4 and the docs.
5. Verification, then the release (T051).
