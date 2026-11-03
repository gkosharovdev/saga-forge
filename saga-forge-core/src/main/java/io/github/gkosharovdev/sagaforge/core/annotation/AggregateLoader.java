package io.github.gkosharovdev.sagaforge.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a client method that loads one aggregate type from its identifier.
 * The method takes exactly one parameter (the identifier) and returns an
 * {@code @Aggregate} type. At most one loader is declared per aggregate type.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AggregateLoader {
}
