package io.github.gkosharovdev.sagaforge.core.spi;

/**
 * SPI that supplies the instance hosting a service-hosted {@code @CommandHandler} method, or an
 * {@code @AggregateLoader} or {@code @AggregateSaver} method.
 *
 * <p>When a command sent through the {@code CommandGateway} is handled by a method on a non-aggregate
 * service class, the engine asks this provider for the instance to invoke it on. Likewise, when it loads or
 * saves an aggregate through a client {@code @AggregateLoader} or {@code @AggregateSaver} method, it asks this
 * provider for an instance of the class declaring that method. Instances are looked up on first use, not when
 * the engine starts. Creation handlers and instance-method handlers of an {@code @Aggregate} do not go through
 * this provider.</p>
 *
 * <p><strong>Implemented by the engine host, never by client code.</strong> The Spring Boot starter will
 * back it with the application context; the test harness backs it with a map.</p>
 *
 * <h2>Null-safety contracts</h2>
 * <ul>
 *   <li>{@link #instanceOf(Class)} — implementations must throw {@link IllegalArgumentException} if
 *       {@code handlerType} is {@code null}; the return value is never {@code null}.</li>
 * </ul>
 *
 * <h2>Missing instances</h2>
 * <p>If no instance of {@code handlerType} is available, implementations must throw an unchecked
 * exception (for example {@link IllegalStateException}, or the host container's own lookup exception)
 * rather than return {@code null}. The engine lets it propagate unchanged and rolls back the current
 * unit of work.</p>
 */
public interface HandlerInstanceProvider {

    /**
     * Returns the instance on which service-hosted {@code @CommandHandler} methods, or
     * {@code @AggregateLoader}/{@code @AggregateSaver} methods, of the given type are invoked.
     *
     * @param handlerType the class declaring the {@code @CommandHandler}, {@code @AggregateLoader} or
     *                    {@code @AggregateSaver} method; must not be {@code null}
     * @return an instance assignable to {@code handlerType}; never {@code null}
     * @throws IllegalArgumentException if {@code handlerType} is {@code null}
     * @throws RuntimeException         if no instance of {@code handlerType} is available
     */
    Object instanceOf(Class<?> handlerType);
}
