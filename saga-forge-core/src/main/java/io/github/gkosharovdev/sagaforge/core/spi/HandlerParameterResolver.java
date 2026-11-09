package io.github.gkosharovdev.sagaforge.core.spi;

import java.lang.reflect.Executable;
import java.lang.reflect.Parameter;

/**
 * SPI supplying the parameters of a {@code @CommandHandler} method or constructor other than its {@code @Command}
 * parameter, which the engine always supplies itself.
 *
 * <p><strong>Implemented by host integrations only, never by client code.</strong> The Spring Boot starter will
 * back it with bean lookup. Several resolvers may be registered; the first that {@linkplain #supports supports} a
 * parameter wins. With none registered, every handler with another parameter is a startup error.</p>
 *
 * <h3>When each method is called</h3>
 * <ul>
 *   <li>{@link #supports(Executable, Parameter)} at engine start, for every such parameter of every registered
 *       handler. It must be free of side effects.</li>
 *   <li>{@link #resolve(Executable, Parameter)} inside the unit of work, on its thread, before each invocation of
 *       that handler. A thrown exception fails the unit of work.</li>
 * </ul>
 *
 * <h3>Null-safety contracts</h3>
 * <p>Both methods must throw {@link IllegalArgumentException} if either argument is {@code null}.</p>
 */
public interface HandlerParameterResolver {

    /**
     * Reports whether {@link #resolve(Executable, Parameter)} can supply the parameter. Free of side effects.
     *
     * @param handler   the {@code @CommandHandler} method or constructor; must not be {@code null}
     * @param parameter one of its parameters other than the {@code @Command} one; must not be {@code null}
     * @return {@code true} if this resolver supplies the parameter
     * @throws IllegalArgumentException if either argument is {@code null}
     */
    boolean supports(Executable handler, Parameter parameter);

    /**
     * Supplies the value of the parameter for one invocation of the handler.
     *
     * @param handler   the {@code @CommandHandler} method or constructor; must not be {@code null}
     * @param parameter one of its parameters that {@link #supports(Executable, Parameter)} accepted; must not be
     *                  {@code null}
     * @return a value assignable to the parameter type; {@code null} only for a non-primitive parameter
     * @throws IllegalArgumentException if either argument is {@code null}
     * @throws RuntimeException         if the value cannot be supplied; the unit of work then fails
     */
    Object resolve(Executable handler, Parameter parameter);
}
