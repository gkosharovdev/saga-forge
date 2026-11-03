package io.github.gkosharovdev.sagaforge.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a domain event class: a fact about one aggregate. The scanner
 * validates that {@code @SagaStep} method parameters are annotated with this.
 * Every domain event names the {@code @Aggregate} type it is about via
 * {@link #aggregate()} and carries exactly one {@code @AggregateIdentifier}
 * field holding that aggregate's identity.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DomainEvent {

    /**
     * The {@code @Aggregate} type this event is about. Required; the event
     * also carries exactly one {@code @AggregateIdentifier} field.
     */
    Class<?> aggregate();
}
