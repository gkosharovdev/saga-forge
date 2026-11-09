package io.github.gkosharovdev.sagaforge.core.spi;

/**
 * SPI supplying a fresh {@code @Saga} object for every unit of work, so sagas can take injected dependencies.
 *
 * <p><strong>Implemented by host integrations only, never by client code.</strong> The Spring Boot starter will
 * back it with prototype beans. At most one provider is registered; without one, the engine uses the saga class's
 * no-argument constructor (any visibility).</p>
 *
 * <h3>When each method is called</h3>
 * <ul>
 *   <li>{@link #supports(Class)} at engine start, for every registered {@code @Saga} class. It must be free of side
 *       effects.</li>
 *   <li>{@link #newInstance(Class)} at the start of every unit of work of that saga type. A thrown exception fails
 *       the unit of work.</li>
 * </ul>
 *
 * <h3>Fields of the returned object</h3>
 * <p>After {@code newInstance} the engine sets the {@code @SagaId} field, the {@code SagaContext}-typed field and the
 * {@code CommandGateway}-typed field, overwriting any value the provider set. Every other field is a dependency: the
 * engine never reads or persists it, and {@link SagaInstance#state()} stays empty.</p>
 *
 * <h3>Null-safety contracts</h3>
 * <p>Both methods must throw {@link IllegalArgumentException} if {@code sagaType} is {@code null}.</p>
 */
public interface SagaObjectProvider {

    /**
     * Reports whether {@link #newInstance(Class)} can create objects of the saga type. Free of side effects.
     *
     * @param sagaType the {@code @Saga}-annotated class; must not be {@code null}
     * @return {@code true} if this provider creates objects of {@code sagaType}
     * @throws IllegalArgumentException if {@code sagaType} is {@code null}
     */
    boolean supports(Class<?> sagaType);

    /**
     * Creates a new saga object for one unit of work.
     *
     * @param sagaType the {@code @Saga}-annotated class that {@link #supports(Class)} accepted; must not be
     *                 {@code null}
     * @return a new object of exactly {@code sagaType}, never one returned before; never {@code null}
     * @throws IllegalArgumentException if {@code sagaType} is {@code null}
     * @throws RuntimeException         if no object can be created; the unit of work then fails
     */
    Object newInstance(Class<?> sagaType);
}
