package io.github.gkosharovdev.sagaforge.core.spi;

import java.util.List;
import java.util.Optional;

/**
 * SPI for durable saga instance persistence.
 *
 * <p>Implementations are provided by storage modules ({@code saga-forge-storage-postgres},
 * {@code saga-forge-storage-mongodb}, {@code saga-forge-storage-mysql}). Each storage module
 * depends only on {@code saga-forge-core} and the respective database driver, following the
 * pluggable module boundary pattern.</p>
 *
 * <h3>Null-safety contracts</h3>
 * <ul>
 *   <li>{@link #save(SagaInstance)} — implementations must throw
 *       {@link IllegalArgumentException} if {@code instance} is {@code null}.</li>
 *   <li>{@link #findById(String)} — implementations must throw
 *       {@link IllegalArgumentException} if {@code sagaId} is {@code null}.</li>
 *   <li>{@link #findByAssociation(String, String)} — implementations must throw
 *       {@link IllegalArgumentException} if either argument is {@code null}.</li>
 *   <li>{@link #delete(String)} — implementations must throw
 *       {@link IllegalArgumentException} if {@code sagaId} is {@code null}.</li>
 * </ul>
 *
 * <h3>Persistence guarantees</h3>
 * <p>The saga engine persists instance state on every state transition. If a storage
 * operation fails, the engine will not advance the saga instance to the next state.
 * Implementations should propagate storage failures as unchecked exceptions.</p>
 */
public interface StorageProvider {

    /**
     * Persists the given saga instance, creating or updating as appropriate.
     *
     * @param instance the saga instance to persist; must not be {@code null}
     * @throws IllegalArgumentException if {@code instance} is {@code null}
     */
    void save(SagaInstance instance);

    /**
     * Retrieves a saga instance by its unique identifier.
     *
     * @param sagaId the saga instance identifier; must not be {@code null}
     * @return an {@link Optional} containing the saga instance, or empty if not found
     * @throws IllegalArgumentException if {@code sagaId} is {@code null}
     */
    Optional<SagaInstance> findById(String sagaId);

    /**
     * Finds all saga instances associated with the given property and value.
     *
     * @param associationProperty the association property name; must not be {@code null}
     * @param associationValue    the association property value; must not be {@code null}
     * @return a list of matching saga instances, never {@code null} (empty if none match)
     * @throws IllegalArgumentException if either argument is {@code null}
     */
    List<SagaInstance> findByAssociation(String associationProperty, String associationValue);

    /**
     * Deletes a saga instance by its unique identifier.
     *
     * @param sagaId the saga instance identifier; must not be {@code null}
     * @throws IllegalArgumentException if {@code sagaId} is {@code null}
     */
    void delete(String sagaId);
}
