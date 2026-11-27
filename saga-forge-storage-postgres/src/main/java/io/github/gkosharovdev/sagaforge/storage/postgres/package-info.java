/**
 * PostgreSQL storage for Saga Forge: the {@link
 * io.github.gkosharovdev.sagaforge.core.spi.StorageProvider} and {@link
 * io.github.gkosharovdev.sagaforge.core.spi.SagaContextStore} implementations backed by JDBC.
 *
 * <p>The module ships its DDL as versioned Flyway migrations at {@link
 * io.github.gkosharovdev.sagaforge.storage.postgres.PostgresSchema#MIGRATION_LOCATION}, one version
 * per schema change ({@link
 * io.github.gkosharovdev.sagaforge.storage.postgres.PostgresSchema#versions()}), meant to be
 * applied under their own history table ({@link
 * io.github.gkosharovdev.sagaforge.storage.postgres.PostgresSchema#DEFAULT_HISTORY_TABLE}) so their
 * numbers never meet the application's. A released version never changes. The module does not
 * depend on Flyway: the Spring Boot starter runs the migrations with {@code
 * initialize-schema=migrate}, and a plain-Java host runs its own Flyway. {@link
 * io.github.gkosharovdev.sagaforge.storage.postgres.PostgresSchemaValidator} checks a database
 * against a version by meaning (tables, columns, constraints; indexes only warn) and detects which
 * released version a database without history is at. The complete current schema {@code schema.sql}
 * and the {@code schema-upgrade-NNN.sql} scripts remain for hosts that apply the DDL themselves.
 *
 * <p>The caller supplies the {@link javax.sql.DataSource} and owns its connection pooling; this
 * module neither creates a pool nor configures one.
 *
 * <p>{@link io.github.gkosharovdev.sagaforge.storage.postgres.JdbcTransactionBoundary} is a
 * plain-JDBC {@link io.github.gkosharovdev.sagaforge.core.spi.TransactionBoundary}: one connection
 * per transaction, bound to the thread, never suspending an active one. A {@link
 * io.github.gkosharovdev.sagaforge.storage.postgres.PostgresStorageProvider} built with a boundary
 * joins its active transaction through {@code resource(java.sql.Connection.class)}; the {@link
 * io.github.gkosharovdev.sagaforge.storage.postgres.PostgresSagaContextStore} never joins. Size the
 * pool for at least two connections per concurrent unit of work: the transaction's and the context
 * store's.
 */
package io.github.gkosharovdev.sagaforge.storage.postgres;
