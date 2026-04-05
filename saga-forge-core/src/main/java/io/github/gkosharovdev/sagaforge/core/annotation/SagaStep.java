package io.github.gkosharovdev.sagaforge.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an event-handling method within a saga. The {@code associationProperty}
 * names the field on the incoming {@code @DomainEvent} class used to correlate
 * the event to the correct saga instance. The method must accept exactly one
 * parameter whose type is annotated with {@code @DomainEvent}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface SagaStep {

    /**
     * The name of the field on the incoming domain event used to correlate
     * the event to the correct saga instance.
     */
    String associationProperty();
}
