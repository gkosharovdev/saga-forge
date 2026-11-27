# Database schema

Saga Forge keeps its state in six Postgres tables: `saga_instance`, `saga_association`, `saga_context`,
`saga_lifecycle_history`, `saga_message_store` and `saga_message_store_leader`. Since 0.5.0,
`saga-forge-storage-postgres` ships them as **versioned Flyway migrations** with their own history table. You no longer
copy Saga Forge's DDL into your own migrations, and Saga Forge's version numbers never meet yours.

| Version | What it adds | First released in |
|---|---|---|
| V1 base schema | the six tables as of 0.1.0 (with the heartbeat table the relay used then) | 0.1.0 |
| V2 saga outcome events | renames `REQUIRES_INTERVENTION` to `COMPENSATION_FAILED` | 0.2.0 |
| V3 saga deadlines | the message store's due time and the `CANCELLED` status | 0.4.0 |
| V4 active standby relay | the leadership table; drops the heartbeat table | 0.4.0 |
| V5 saga operations | resolutions, compensation rounds, operator columns and indexes | 0.5.0 |
| V6 process metadata | a `metadata` JSONB column on the instance, history and message-store tables | 0.5.0 |

The migrations live at `classpath:io/github/gkosharovdev/sagaforge/storage/postgres/migration`, and Flyway records them
in `saga_forge_schema_history`. A released version is never changed: a later schema change is always a new version.

## Choose a mode

The starter's `saga-forge.storage.postgres.initialize-schema` decides what happens at startup:

| Mode | Writes to the database | Fails startup when | Use it for |
|---|---|---|---|
| `never` (default) | never | a Saga Forge table is missing | schemas someone else manages; differences are only logged |
| `always` | creates the tables from `schema.sql` in a database without them | only some tables exist | local development and tests |
| `validate` | never | the schema check finds a missing or different table, column or constraint | Liquibase or DBA-managed schemas |
| `migrate` | applies the missing versions under Saga Forge's history | Flyway is absent, the tables match no version, a version fails, or the check fails afterwards | production with Flyway |

Every mode except `always` on an empty database runs the **schema check**. It compares the database with the version
the jar expects, by meaning, not by text:

- tables, columns (type and `NOT NULL`), primary keys, unique constraints, foreign keys (with their `ON DELETE`) and
  check constraints must be there; any difference is a **failure**;
- a missing or different index is only a **warning**: the library works without it, more slowly;
- extra tables, columns, constraints and indexes are ignored, so you can add your own.

A failure lists every difference, one per line:

```text
Saga Forge's schema does not match V6 (process metadata): 1 failure, 0 warnings.
  FAILURE saga_context: column last_modified_at is missing (expected timestamp with time zone NOT NULL)
```

## `migrate`: Saga Forge's migrations beside your own

Add Flyway's core and Postgres modules (Spring Boot manages their versions; an application that uses Flyway already
has them):

```xml
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-database-postgresql</artifactId>
</dependency>
```

```properties
saga-forge.storage.postgres.initialize-schema=migrate
```

At startup, Spring Boot's own database initialisers run first: your Flyway migrations (`flyway_schema_history`),
Liquibase, `spring.sql.init`. Then Saga Forge runs its own Flyway instance on its own location and history table,
then checks the result, then starts the runtime. Your Flyway settings (`spring.flyway.*`) don't apply to it, and
`spring.flyway.enabled=false` doesn't stop it. Several instances starting at once are safe: Flyway's lock lets one
apply each version and the others find it applied.

Because your tables are already in the schema, Saga Forge's history starts with a baseline row at version 0, then
records V1 to V6:

```text
 version | description             | type
---------+-------------------------+----------
 0       | << Flyway Baseline >>   | BASELINE
 1       | base schema             | SQL
 ...
 6       | process metadata        | SQL
```

Never copy Saga Forge's migrations into your own migration location: the numbers would clash with yours, and each
upgrade of Saga Forge would mean copying again.

| Property | Default | Meaning |
|---|---|---|
| `saga-forge.storage.postgres.migration.history-table` | `saga_forge_schema_history` | Saga Forge's Flyway history table. |
| `saga-forge.storage.postgres.migration.baseline-version` | unset | The version an existing database without Saga Forge's history is at; replaces detection. |

## Taking over an existing database

A database created before 0.5.0 (from `schema.sql`, the upgrade scripts, or your own copy of them in your migrations)
has Saga Forge's tables but no Saga Forge history. The first start with `migrate` detects which released version the
tables match, records it as the baseline, and applies only the later versions. Every row is kept; V2 renames
`REQUIRES_INTERVENTION` to `COMPENSATION_FAILED` and V4 drops the heartbeat table, as the upgrade scripts did. If your
own migrations created Saga Forge's tables, your history stays as it is.

If the tables match no released version, for example because a column was dropped or a constraint changed by hand,
startup fails before anything is written and names the closest version and the differences:

```text
Saga Forge's tables exist but match no released schema version. Closest: V4 (active standby relay). Saga Forge's
schema does not match V4 (active standby relay): 1 failure, 0 warnings.
  FAILURE saga_instance: column outcome is missing (expected text NULL)
```

Repair the listed differences, or set `saga-forge.storage.postgres.migration.baseline-version` to the version the
database is at: Saga Forge then applies the later versions and checks the result.

## `validate`: schemas you manage yourself

With Liquibase, or with a DBA who applies DDL by hand, apply `schema.sql` for a new database or the matching
`schema-upgrade-NNN.sql` scripts for an existing one (see [Operations](operations.md#schema-and-upgrades)), and set:

```properties
saga-forge.storage.postgres.initialize-schema=validate
```

Startup then fails on any missing or different table, column or constraint, listing each one, and logs a warning for a
missing index. It never writes and never creates a history table.

## Plain Java

Without the starter, run the same migrations with your own Flyway and check the result with
`PostgresSchemaValidator`:

```java
static void migrate(DataSource dataSource) {
  PostgresSchemaValidator validator = new PostgresSchemaValidator(dataSource);
  String history = PostgresSchema.DEFAULT_HISTORY_TABLE;

  // A database built from schema.sql and the upgrade scripts has Saga Forge's tables but no
  // history: record the version it is at as the baseline. Otherwise the baseline is 0, which
  // lets Flyway start beside the application's own tables.
  String baseline = "0";
  if (validator.appliedVersion(history).isEmpty()) {
    switch (validator.detectVersion()) {
      case SchemaDetection.Matched matched ->
          baseline = String.valueOf(matched.version().number());
      case SchemaDetection.Unmatched unmatched -> throw SagaSchemaException.unmatched(unmatched);
      case SchemaDetection.Empty empty -> {}
    }
  }

  Flyway.configure()
      .dataSource(dataSource)
      .locations(PostgresSchema.MIGRATION_LOCATION)
      .table(history)
      .placeholderReplacement(false)
      .baselineOnMigrate(true)
      .baselineVersion(baseline)
      .load()
      .migrate();

  SchemaValidationReport report = validator.validate();
  if (!report.passed()) {
    throw SagaSchemaException.failedCheck(report);
  }
}
```

Source: `saga-forge-storage-postgres/src/test/java/io/github/gkosharovdev/sagaforge/storage/postgres/PlainJavaMigrationExampleTest.java`.

`saga-forge-storage-postgres` does not depend on Flyway: add `flyway-core` and `flyway-database-postgresql` to your own
build.

## Rolling deploys

During a rolling deploy, instances of the old and the new release run against one database. The new release's first
instance applies the new version; the old instances keep running on it only if that version keeps the previous
release working. Each release's notes say whether its schema version does. When it doesn't, stop every instance
before deploying, as the notes say. With `migrate`, an older jar against a newer database starts: Flyway ignores
versions it doesn't know.
