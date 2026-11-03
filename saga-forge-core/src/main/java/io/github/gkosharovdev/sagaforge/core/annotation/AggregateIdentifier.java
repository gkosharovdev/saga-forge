package io.github.gkosharovdev.sagaforge.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the field holding an aggregate's identity. Exactly one such field is
 * declared on each {@code @Aggregate} and each {@code @DomainEvent} type, and
 * at most one on each {@code @Command} type. On a command it names the target
 * aggregate.
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AggregateIdentifier {
}
