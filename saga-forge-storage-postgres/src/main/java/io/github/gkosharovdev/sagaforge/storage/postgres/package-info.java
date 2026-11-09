/**
 * PostgreSQL storage for Saga Forge: the {@link io.github.gkosharovdev.sagaforge.core.spi.StorageProvider}
 * and {@link io.github.gkosharovdev.sagaforge.core.spi.SagaContextStore} implementations backed by JDBC.
 *
 * <p>The module ships its DDL as the classpath resource
 * {@code io/github/gkosharovdev/sagaforge/storage/postgres/schema.sql}. It does not create or migrate the
 * schema itself: the host application applies {@code schema.sql} however it already manages its schema.</p>
 *
 * <p>The caller supplies the {@link javax.sql.DataSource} and owns its connection pooling; this module
 * neither creates a pool nor configures one.</p>
 *
 * <p>{@link io.github.gkosharovdev.sagaforge.storage.postgres.JdbcTransactionBoundary} is a plain-JDBC
 * {@link io.github.gkosharovdev.sagaforge.core.spi.TransactionBoundary}: one connection per transaction, bound to
 * the thread, never suspending an active one. A
 * {@link io.github.gkosharovdev.sagaforge.storage.postgres.PostgresStorageProvider} built with a boundary joins its
 * active transaction through {@code resource(java.sql.Connection.class)}; the
 * {@link io.github.gkosharovdev.sagaforge.storage.postgres.PostgresSagaContextStore} never joins. Size the pool for
 * at least two connections per concurrent unit of work: the transaction's and the context store's.</p>
 */
package io.github.gkosharovdev.sagaforge.storage.postgres;
