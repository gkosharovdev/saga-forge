package io.github.gkosharovdev.sagaforge.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method or constructor that handles a command. The handler takes
 * exactly one parameter whose type is annotated with {@code @Command}.
 *
 * <p>On an {@code @Aggregate} class, a handler is either an instance method,
 * for which the target aggregate is loaded first, or a constructor / static
 * factory method, which creates a new aggregate. On a service class, a
 * handler is a method. A handler is never declared on a {@code @Saga} class.
 */
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CommandHandler {
}
