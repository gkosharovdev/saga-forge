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
 */
package io.github.gkosharovdev.sagaforge.storage.postgres;
