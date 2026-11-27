# Feature Specification: Versioned Schema Migrations

**Feature Branch**: `018-versioned-schema-migrations`

**Created**: 2026-11-27

**Status**: Draft

**Input**: User description: "018 Versioned schema migrations (roadmap SF-7, closes G-003-25, removes booking-service
workaround W11). The Postgres storage module ships schema.sql plus schema-upgrade-0NN.sql scripts for the host to apply
by hand; Flyway users copy them under their own version numbers and guard against drift. Scope: Flyway-compatible
versioned migration resources shipped in saga-forge-storage-postgres under their own location (e.g.
db/migration/saga-forge/postgres), applied with a separate history table so numbering never clashes with the
application's own migrations; the Spring Boot starter runs them when Flyway is on the class path
(saga-forge.storage.postgres.initialize-schema=migrate); a validate mode checks tables, columns and constraints at
startup and fails with a clear difference. Must cover existing databases created from schema.sql/upgrade scripts
(baseline). Out of scope: Liquibase (on request), MySQL and MongoDB. Acceptance: an application with its own Flyway
migrations at V1..V62 starts with Saga Forge migrations applied and both histories intact; a missing column fails
validate with its name."

## Context

`saga-forge-storage-postgres` ships `schema.sql` (the current schema) and one `schema-upgrade-NNN.sql` per feature that
changed it (005, 006, 007, 010, 011, 013, 015, 017). The host applies them by hand or copies them into its own
migration tool (G-003-25). The starter's `initialize-schema` only creates the tables in an empty database (`always`) or
checks that they exist (`never`, the default).

An application on Flyway has to copy `schema.sql` into one of its own migrations (say `V62`) and guard the copy
against drift, for example with a test that compares it with the jar's resource, so a library upgrade that changes the
schema is noticed. Every later feature that changes the schema (015b, 019) would add another hand-copied script.
(Correction, 2026-11-27: the input above names booking-service's workaround W11; booking-service never adopted Saga
Forge, so it has no copied schema or drift test. The `V62` case is illustrative.)

This feature ships the schema as versioned migrations that a migration tool applies under Saga Forge's own history,
lets the starter apply them, and adds a startup check that the database has the shape the library needs. Flyway is the
one tool supported. Liquibase is out of scope until someone asks, and so are MySQL and MongoDB (Constitution Principle
IV: they have no storage yet).

Released schema levels, from the release tags: 0.1.0 has `schema.sql` with the 005, 006, 007 and 010 changes; 0.2.0 and
0.3.0 add 011; 0.4.0 adds 013 and 017. 015's change is unreleased and ships with the next release.

## Decisions Taken In This Spec

- **D1 — Migrations are plain SQL resources, versioned by schema level.** The Postgres module ships Flyway-named SQL
  files under its own package path, `io/github/gkosharovdev/sagaforge/storage/postgres/migration`, and takes no Flyway
  dependency. The request's example location, `db/migration/saga-forge/postgres`, is not used: Flyway scans its
  default location `db/migration` recursively, so an application's own Flyway would apply Saga Forge's files under the
  application's history (plan research R2). Every released schema level is exactly
  one applied version, so a database's level can be named by a version. The first version is the schema as released in
  0.1.0; each later version is one released set of changes. The plan fixes the numbering, and whether 013, 015 and 017
  are separate versions or one per release.
- **D2 — Own history table.** Saga Forge's migrations are recorded in their own history table, by default
  `saga_forge_schema_history`, in the same database schema as Saga Forge's tables. The application's migration history
  is never read or written, so the two numberings never clash.
- **D3 — Two new modes on the existing property.** `saga-forge.storage.postgres.initialize-schema` gains `migrate` and
  `validate` beside `never` and `always`. The default stays `never`: no schema change happens unless the application
  asks for it.
- **D4 — Baseline by detection.** A database that has Saga Forge's tables but no Saga Forge history (created from
  `schema.sql`, upgrade scripts or a copied migration) is recognised by its shape. `migrate` records the newest released
  level the database fully matches as its baseline, then applies the later versions. A database that matches no
  released level fails with the differences from the closest level, and nothing is changed. An application may name the
  baseline version itself, which skips detection.
- **D5 — Run after the application's own migrations.** When the application also runs Flyway, Saga Forge migrates after
  the application's migrations and before the runtime starts. So an application whose own migration created Saga Forge's
  tables (a copied `V62`) keeps that migration unchanged, and Saga Forge baselines over it.
- **D6 — What `validate` checks.** It checks the tables, columns (name, type and nullability) and constraints (primary
  key, unique, foreign key, check) that the running library version needs. If any is missing or different, startup
  fails with a message that lists every difference by table and column or constraint name. Missing indexes are reported
  as warnings and don't fail startup. Extra tables, columns and indexes are allowed. `validate` needs no migration tool
  and works the same whatever applied the schema.
- **D7 — `migrate` validates after migrating.** After applying versions, `migrate` runs the same check as `validate`,
  so a migrated database that still differs (for example after a manual change) fails at startup, not on first use.
- **D8 — The scripts stay, and stay equal.** `schema.sql` and the `schema-upgrade-NNN.sql` scripts keep shipping for
  hosts without Flyway. A test proves that applying every migration, and applying `schema.sql`, give the same schema.
  Another proves that each upgrade path from a released level ends there too. `always` keeps creating from
  `schema.sql`.
- **D9 — Without Spring.** The migrations work with any Flyway setup pointed at their location and history table. The
  schema check is also usable from plain Java over a `DataSource`, so a host without the starter can run it. Flyway code
  lives only in the starter, as an optional dependency (Constitution Principle I; the storage module keeps plain JDBC).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - An application on Flyway gets Saga Forge's schema applied for it (Priority: P1)

An application that manages its own schema with Flyway adds the starter, sets `initialize-schema=migrate` and starts.
Saga Forge's tables are created, or brought to the current level, under Saga Forge's own history. The application's
own migrations and history are untouched.

**Why this priority**: This removes the hand-copied script and the drift guard. Every later schema change then
reaches adopters without manual work.

**Independent Test**: Start a Spring application with its own Flyway migrations V1 to V62 against an empty Postgres
database with `initialize-schema=migrate`. Both histories are complete and the canonical saga runs to completion.

**Acceptance Scenarios**:

1. **Given** an empty database and an application with its own Flyway migrations V1 to V62, **When** the application
   starts with `migrate`, **Then** the application's history lists V1 to V62, Saga Forge's history lists every Saga
   Forge version, and a saga completes.
2. **Given** a database migrated by the previous library release, **When** the application starts with a library
   release that adds a version, **Then** only the new version is applied and recorded in Saga Forge's history.
3. **Given** a database already at the library's level, **When** the application starts with `migrate`, **Then**
   nothing is applied and startup is not slower than one history read and one schema check.
4. **Given** `migrate` and no Flyway on the class path, **When** the application starts, **Then** startup fails saying
   that `migrate` needs Flyway and naming the dependency to add.

---

### User Story 2 - An existing database is taken over without loss (Priority: P1)

An application whose database was created from `schema.sql` and the upgrade scripts, or from its own copied
migration, switches to `migrate`. Saga Forge recognises the database's level, records it as the baseline and applies
only what is missing. Existing instances, context values, history and messages are kept.

**Why this priority**: An application that already has the tables, from `schema.sql`, the upgrade scripts or a copied migration, cannot
start over. Without a takeover,
`migrate` is only for new databases.

**Independent Test**: Create a database from 0.1.0's `schema.sql` with rows in every table, then start with `migrate`.
The recorded baseline is the 0.1.0 level, the later versions are applied, the rows are still there, and a saga started
before the switch completes after it.

**Acceptance Scenarios**:

1. **Given** a database at the 0.1.0 level with no Saga Forge history, **When** the application starts with `migrate`,
   **Then** Saga Forge's history shows the 0.1.0 version as baseline and the later versions as applied, and no row is
   lost.
2. **Given** a database at the 0.4.0 level, created from `schema.sql` plus upgrade scripts, **When** the application
   starts with `migrate`, **Then** the 0.4.0 version is the baseline and only later versions are applied.
3. **Given** a database whose Saga Forge tables match no released level (for example a column dropped by hand),
   **When** the application starts with `migrate`, **Then** startup fails listing the differences from the closest
   level, and the database and both histories are unchanged.
4. **Given** an application that names the baseline version itself, **When** it starts with `migrate`, **Then** that
   version is recorded as baseline without detection, and the later versions are applied.

---

### User Story 3 - Startup refuses a database the library cannot use (Priority: P2)

An application that applies the schema by other means (its own tool, a DBA, Liquibase) sets
`initialize-schema=validate`. If a table, column or constraint the library needs is missing or different, the
application does not start, and the message names each difference.

**Why this priority**: Today a missing column shows up as an SQL error on the first saga that needs it. That can be
hours after a deploy, and it can strand an instance mid-step.

**Independent Test**: Drop one column from a correctly migrated database and start with `validate`. Startup fails, and
the message names the table and the column.

**Acceptance Scenarios**:

1. **Given** a database at the library's level, **When** the application starts with `validate`, **Then** it starts and
   nothing is written to the database.
2. **Given** a database missing one column, **When** the application starts with `validate`, **Then** startup fails,
   and the message names the table, the column and the expected type.
3. **Given** a database with a column of the wrong type, a missing check constraint and an extra application column,
   **When** the application starts with `validate`, **Then** startup fails and lists the type and the constraint, not
   the extra column.
4. **Given** a database missing only an index, **When** the application starts with `validate`, **Then** it starts and
   logs a warning naming the index.

---

### User Story 4 - Hosts without the starter use the same migrations and check (Priority: P3)

A host that assembles the runtime by hand points its own Flyway at Saga Forge's location and history table, and calls
the schema check over its `DataSource` before starting the runtime.

**Why this priority**: The starter is the main path. Plain Java hosts (and a future Quarkus extension, G-007-01)
need the same resources, so they mustn't be tied to Spring.

**Independent Test**: Without Spring, apply the migrations with Flyway's API using the documented location and history
table, then run the check. It passes, and it fails with a dropped column.

**Acceptance Scenarios**:

1. **Given** the documented location and history table, **When** a host runs Flyway over an empty database, **Then**
   the schema equals one created from `schema.sql`.
2. **Given** a database missing a column, **When** a host runs the check from plain Java, **Then** it reports the same
   differences as the starter's `validate`.

---

### Edge Cases

- **Several instances start at once** (013's active/standby setup, rolling deploys): exactly one applies the versions.
  The others wait, then find the database at the level and only validate. No version is applied twice.
- **A migration fails halfway**: each version runs in one transaction (Postgres has transactional DDL). The failed
  version is not recorded, the database stays at the previous level, and startup fails naming the version and the
  database error.
- **The database is newer than the library** (a rollback to an older jar after a newer one migrated): versions the
  library doesn't know are not an error for `migrate`. The check passes as long as everything the older library needs
  is there. Whether each version keeps the previous release working is a property of that version, to be recorded per
  migration (recorded as a gap, FR-022).
- **The application's Flyway uses a schema other than the default, or `spring.flyway.*` placeholders**: Saga Forge's
  migrations run against the schema its storage uses, which is the `DataSource` connection's default schema as today.
  The application's Flyway settings aren't inherited, except the `DataSource`.
- **`always` on a database that has Saga Forge history**: behaves as today (all tables exist, so nothing is created).
  The documentation points to `migrate` for production.
- **Spring Modulith interop**: Modulith's publication registry tables are Modulith's. They are not part of the check or
  the migrations.
- **Flyway present, but the application doesn't use it** (no `spring.flyway` migrations): `migrate` still works. The
  starter runs only Saga Forge's migrations.
- **The application turns off Spring Boot's own Flyway** (`spring.flyway.enabled=false`): Saga Forge's `migrate` still
  runs. It doesn't depend on the application's Flyway bean.

## Requirements *(mandatory)*

### Functional Requirements

**Migrations (storage module)**

- **FR-001**: The Postgres storage module MUST ship Saga Forge's schema as versioned SQL migrations under a location no
  Flyway default location contains (`io/github/gkosharovdev/sagaforge/storage/postgres/migration`), named so that
  Flyway applies them in order.
- **FR-002**: The first version MUST create the schema as released in 0.1.0. Each later version MUST hold one released
  set of changes, so that every released schema level equals "all versions up to N applied" for exactly one N.
- **FR-003**: Applying all versions to an empty database MUST give the same tables, columns, types, nullability,
  defaults, constraints and indexes as applying the current `schema.sql`. Applying `schema.sql` of any released level
  plus its upgrade scripts MUST give the same result too. A container test MUST prove both for every released level.
- **FR-004**: The storage module MUST NOT depend on a migration tool.
- **FR-005**: `schema.sql` and the `schema-upgrade-NNN.sql` scripts MUST keep shipping. Every future schema change MUST
  add a new version and update `schema.sql` (and, while the scripts are kept, an upgrade script) in the same change.
  Editing a released version MUST be prevented by a test (released versions are frozen by checksum).

**History and baseline**

- **FR-006**: Saga Forge's applied versions MUST be recorded in their own history table, by default
  `saga_forge_schema_history`, in the database schema of Saga Forge's tables. The name MUST be configurable. The
  application's migration history MUST NOT be read or written.
- **FR-007**: When Saga Forge's tables exist and its history table doesn't, `migrate` MUST detect the newest released
  level that the database fully matches, record it as the baseline and apply only later versions. Rows in Saga Forge's
  tables MUST be kept.
- **FR-008**: If the existing tables match no released level, `migrate` MUST fail before changing anything and list the
  differences from the closest level, in the format of FR-012.
- **FR-009**: The application MUST be able to name the baseline version itself, which replaces detection.
- **FR-010**: When several application instances run `migrate` against one database at once, each version MUST be
  applied exactly once. Instances that didn't apply it MUST wait for it to finish and then continue with validation.

**Schema check**

- **FR-011**: A schema check MUST compare the database with what the running library version needs: tables, columns
  (name, type, nullability) and constraints (primary key, unique, foreign key, check, compared by meaning rather than by
  generated name). It MUST also compare the indexes the storage relies on.
- **FR-012**: Each difference MUST name the table and the column, constraint or index, with the expected and the found
  shape. All differences MUST be reported together, not only the first.
- **FR-013**: Missing or different tables, columns and constraints MUST fail the check. Missing indexes MUST be reported
  as warnings. Extra tables, columns, constraints and indexes MUST be ignored.
- **FR-014**: The check MUST be usable without Spring, over a `DataSource`, and MUST NOT write to the database.

**Starter**

- **FR-015**: `saga-forge.storage.postgres.initialize-schema` MUST accept `never` (default, unchanged), `always`
  (unchanged), `validate` (run the check; fail startup on a failing difference) and `migrate` (apply missing versions
  with Flyway, then run the check).
- **FR-016**: The starter MUST run `migrate` after the application's own Flyway migrations, when there are any, and
  before the saga runtime starts. It MUST work whether or not Spring Boot's own Flyway migration is enabled.
- **FR-017**: `migrate` without Flyway on the class path MUST fail startup through a failure analyzer that names the
  property, the value and the dependency to add. A failing check or a failing baseline detection MUST fail startup
  through a failure analyzer that prints the differences.
- **FR-018**: The starter's Flyway support MUST be optional: an application without Flyway MUST start with `never`,
  `always` or `validate`.
- **FR-019**: New properties (history table name, baseline version) MUST follow the starter's conventions:
  configuration metadata, the starter README's property table, and `ReadmePropertiesTest`.
- **FR-020**: The `sagaforge` Actuator endpoint MUST show the schema's current Saga Forge version and the check's last
  result (passed, or the number of failing differences and warnings).

**Documentation and register**

- **FR-021**: The user guide MUST have a schema page covering the four modes, adopting an existing database (baseline),
  running the migrations with Flyway without the starter, the check from plain Java, and the rule that applications
  never copy Saga Forge's migrations into their own. Every snippet MUST come from compiling code. The starter README,
  `docs/agents/saga-forge/SKILL.md`, `docs/agents/AGENTS-snippet.md` and `llms-full.txt` MUST be updated.
- **FR-022**: `.specify/memory/memory.md` MUST close G-003-25. It MUST record new gaps: Liquibase not supported;
  MySQL/MongoDB migrations to follow with their storage; whether a version keeps the previous release working (rolling
  deploys) is not enforced. `AGENTS.md`'s status and module map MUST be updated.

### Key Entities

- **Schema version**: one versioned migration. It has a number, a description, and the SQL that takes the schema from
  the previous level to this one. It is frozen once released.
- **Schema level**: the state a database is in after the versions up to N have been applied. Each release has one.
- **Saga Forge schema history**: the record of the versions applied to one database, kept apart from the application's
  history. The first entry is either an applied first version or a detected or named baseline.
- **Schema difference**: one mismatch between the database and the expected level. It has the table, the column,
  constraint or index, the expected and the found shape, and a severity (failure or warning).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: An application with 62 of its own migrations starts against an empty database with Saga Forge's schema
  applied. Both histories are complete, and no Saga Forge SQL is copied into the application.
- **SC-002**: A database at each released level (0.1.0, 0.2.0/0.3.0, 0.4.0) holding rows in every Saga Forge table is
  taken over by `migrate` with 0 rows lost. A saga started before the switch completes after it.
- **SC-003**: For every released level, the migrated schema is identical to the schema created by the current
  `schema.sql`. Zero differences are reported.
- **SC-004**: A database with one dropped column fails `validate`, and the message names that table and column. One
  test case per Saga Forge table proves this.
- **SC-005**: With three application instances starting against one empty database at once, each version is applied
  exactly once and all three start.
- **SC-006**: Withdrawn (2026-11-27). It asked booking-service to confirm it could drop its schema drift test, but
  booking-service never adopted Saga Forge and has no such test. SC-001 and SC-002 cover the outcome.

## Assumptions

- Postgres is the only storage (MySQL and MongoDB have none yet). Its transactional DDL lets each version run in one
  transaction.
- The Flyway version is the one Spring Boot 4.1 manages. Flyway is an optional dependency of the starter only, and never
  of core, runtime or the storage module.
- Databases created before 0.1.0 (unreleased snapshots) are not supported for baseline detection. They can name a
  baseline themselves.
- Saga Forge's tables live in the `DataSource` connection's default schema, as today. Supporting a separate schema for
  Saga Forge's tables is not part of this feature.
- A version may rewrite rows as well as change the schema. 011's does: it renames the status `REQUIRES_INTERVENTION`
  and deletes that history kind. Such changes run inside the version's transaction, as the upgrade scripts do today.
- The application's migrations don't depend on Saga Forge's tables existing first. An application whose migrations
  reference them keeps applying the schema itself and uses `validate`.
- This builds on 016's branch: 016 isn't merged to master yet, and 018 is branched from it.
