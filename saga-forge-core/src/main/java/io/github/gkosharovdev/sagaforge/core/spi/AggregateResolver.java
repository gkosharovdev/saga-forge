package io.github.gkosharovdev.sagaforge.core.spi;

import java.util.Optional;

/**
 * SPI through which the engine loads and saves {@code @Aggregate} instances on behalf of
 * {@code @CommandHandler} methods, when the client has not supplied its own loader or saver.
 *
 * <p><strong>Implemented by host integrations only, never by client code.</strong> Client code never
 * implements a Saga Forge interface to get its aggregates loaded or saved (spec FR-013). The Spring Boot
 * starter will adapt the client's existing Spring Data repositories (JPA for Postgres/MySQL, Spring Data
 * MongoDB); the test harness provides an in-memory implementation.</p>
 *
 * <h3>Precedence</h3>
 * <ul>
 *   <li><em>Loading</em> an aggregate for an instance-method handler uses the client's
 *       {@code @AggregateLoader} method for that aggregate type if one exists, otherwise the first
 *       resolver that {@linkplain #supports(Class) supports} the type.</li>
 *   <li><em>Saving</em> uses the client's {@code @AggregateSaver} method for that aggregate type if one
 *       exists, otherwise {@link #save(Object)} of a supporting resolver, otherwise nothing: the client's
 *       own unit of work persists the change (for example JPA dirty checking).</li>
 * </ul>
 *
 * <h3>Absence</h3>
 * <p>{@link #load(Class, Object)} returns {@link Optional#empty()} when no aggregate exists for the id;
 * it does not throw. The engine translates absence into an {@code AggregateNotFoundException} carrying
 * the aggregate type and id. {@code Optional} is used rather than a nullable return to match
 * {@link StorageProvider#findById(String)} and to make absence explicit in the signature.</p>
 *
 * <h3>Null-safety contracts</h3>
 * <ul>
 *   <li>{@link #supports(Class)} — implementations must throw {@link IllegalArgumentException} if
 *       {@code aggregateType} is {@code null}.</li>
 *   <li>{@link #load(Class, Object)} — implementations must throw {@link IllegalArgumentException} if
 *       either argument is {@code null}; the return value is never {@code null}.</li>
 *   <li>{@link #save(Object)} — implementations must throw {@link IllegalArgumentException} if
 *       {@code aggregate} is {@code null}.</li>
 * </ul>
 *
 * <h3>Failure propagation</h3>
 * <p>Implementations should propagate storage failures as unchecked exceptions. The engine lets them
 * propagate unchanged and rolls back the current unit of work.</p>
 */
public interface AggregateResolver {

    /**
     * Reports whether this resolver can load and save aggregates of the given type.
     *
     * @param aggregateType the {@code @Aggregate}-annotated class; must not be {@code null}
     * @return {@code true} if {@link #load(Class, Object)} and {@link #save(Object)} apply to the type
     * @throws IllegalArgumentException if {@code aggregateType} is {@code null}
     */
    boolean supports(Class<?> aggregateType);

    /**
     * Loads the aggregate of the given type with the given identifier.
     *
     * @param aggregateType the {@code @Aggregate}-annotated class; must not be {@code null}
     * @param aggregateId   the value of the command's {@code @AggregateIdentifier}; must not be {@code null}
     * @return an {@link Optional} containing the aggregate, or empty if none exists; never {@code null}
     * @throws IllegalArgumentException if either argument is {@code null}
     */
    Optional<Object> load(Class<?> aggregateType, Object aggregateId);

    /**
     * Persists the given aggregate, creating or updating as appropriate, inside the current unit of work.
     *
     * @param aggregate the aggregate to persist; must not be {@code null}
     * @throws IllegalArgumentException if {@code aggregate} is {@code null}
     */
    void save(Object aggregate);
}
