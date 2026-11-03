package io.github.gkosharovdev.sagaforge.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a client method that persists one aggregate type. The method takes
 * exactly one parameter whose type is annotated with {@code @Aggregate}.
 * A saver is optional; at most one is declared per aggregate type.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AggregateSaver {
}
