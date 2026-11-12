package io.github.gkosharovdev.sagaforge.core.spi;

import java.util.Optional;

/**
 * SPI for durable persistence of a saga instance's {@code SagaContext} values, as a
 * {@link SagaContextRecord} separate from the {@link SagaInstance} record (FR-024).
 *
 * <p>Implementations are provided by the storage modules ({@code saga-forge-storage-postgres},
 * {@code saga-forge-storage-mongodb}, {@code saga-forge-storage-mysql}), next to their
 * {@link StorageProvider}. The only consumer is the saga engine.</p>
 *
 * <h2>Null-safety contracts</h2>
 * <ul>
 *   <li>{@link #find(String)} — implementations must throw
 *       {@link IllegalArgumentException} if {@code sagaId} is {@code null}.</li>
 *   <li>{@link #save(SagaContextRecord)} — implementations must throw
 *       {@link IllegalArgumentException} if {@code record} is {@code null}.</li>
 *   <li>{@link #delete(String)} — implementations must throw
 *       {@link IllegalArgumentException} if {@code sagaId} is {@code null}.</li>
 * </ul>
 *
 * <h2>Independence from the saga-scoped unit of work</h2>
 * <p>A {@link #save(SagaContextRecord)} MUST NOT join the saga-scoped unit of work's transaction, so
 * context values written by a step stay queryable by later steps whether that step's unit of work
 * committed or rolled back. Postgres and MySQL modules store records in their own table and write them in
 * a separate transaction; the MongoDB module uses its own collection.</p>
 *
 * <h2>Write order</h2>
 * <p>A record is saved only after its instance's creation write committed: the engine commits the new
 * {@link SagaStatus#CREATED} instance in its own write before the first unit of work runs. A store therefore may
 * rely on the instance record existing (Postgres: the {@code saga_context} foreign key). It never joins the unit of
 * work's transaction.</p>
 *
 * <h2>Reference and values</h2>
 * <p>{@code record.sagaId()} equals the {@link SagaInstance#sagaId()} it belongs to. A record may exist
 * for a saga whose instance record is {@link SagaStatus#FAILED}. Values are stored as given; structured
 * values are nested {@code Map<String, Object>}. Serializing value types is the storage module's concern,
 * as for {@link SagaInstance#state()}. Storage failures propagate as unchecked exceptions.</p>
 */
public interface SagaContextStore {

    /**
     * Retrieves the context record of a saga instance.
     *
     * @param sagaId the saga instance identifier; must not be {@code null}
     * @return an {@link Optional} containing the record, or empty if none was ever saved
     * @throws IllegalArgumentException if {@code sagaId} is {@code null}
     */
    Optional<SagaContextRecord> find(String sagaId);

    /**
     * Creates or replaces the context record for {@code record.sagaId()}, independently of any
     * saga-scoped unit of work.
     *
     * @param record the record to persist; must not be {@code null}
     * @throws IllegalArgumentException if {@code record} is {@code null}
     */
    void save(SagaContextRecord record);

    /**
     * Removes the context record of a saga instance, if any.
     *
     * @param sagaId the saga instance identifier; must not be {@code null}
     * @throws IllegalArgumentException if {@code sagaId} is {@code null}
     */
    void delete(String sagaId);
}
