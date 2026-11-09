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
 *
 * <p>A step returns {@code void}. It never returns a command; instead it
 * dispatches any {@code @Command} it issues through {@code CommandGateway}.</p>
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

    /**
     * How often the engine may run this step's unit of work after a transient failure, and how long it waits in
     * between ({@code specs/005-step-retry}). The default allows one attempt, so a step without a policy is never
     * retried. Validated by the scanner (rule R19, Property 29).
     *
     * @return the step's retry policy
     */
    RetryPolicy retry() default @RetryPolicy;
}
