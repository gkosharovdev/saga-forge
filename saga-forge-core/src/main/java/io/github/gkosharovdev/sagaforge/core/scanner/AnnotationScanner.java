package io.github.gkosharovdev.sagaforge.core.scanner;

import java.util.Collection;

/**
 * Validates saga class definitions against the annotation model rules.
 * Implementations discover and validate @Saga-annotated classes at startup.
 */
public interface AnnotationScanner {

    /**
     * Validates a single saga class against all annotation rules.
     *
     * @param sagaClass the class annotated with @Saga
     * @return validation result containing any errors found
     * @throws IllegalArgumentException if sagaClass is null
     */
    SagaValidationResult validate(Class<?> sagaClass);

    /**
     * Validates a whole model in one pass and never stops at the first error.
     *
     * <p>Rules (specs/002-transactional-step-sequence/contracts/validation.md):
     * <ul>
     *   <li>every {@code @Saga} class: the rules of {@link #validate(Class)};</li>
     *   <li>every other class declaring a {@code @CommandHandler}, {@code @AggregateLoader}, or
     *       {@code @AggregateSaver} (a <em>host</em>): the handler, loader, and saver shape rules;</li>
     *   <li>every {@code @Aggregate}, {@code @DomainEvent}, and {@code @Command} type, passed in or reached
     *       from a step, handler, loader, or saver signature: the identifier rules;</li>
     *   <li>across all hosts: one handler per command type, one loader and one saver per aggregate type,
     *       and an {@code @AggregateIdentifier} on every command handled by an aggregate instance method.
     *       These are reported as {@link ValidationReport#modelErrors() model errors}.</li>
     * </ul>
     *
     * <p>Every passed-in {@code @Saga} class and host gets a {@link SagaValidationResult}, even when valid.
     * An aggregate, event, or command type that is not a host gets a result only when it has errors, and
     * classes none of the rules apply to produce no result. A class listed or reached more than once is
     * validated once.
     *
     * @param classes the classes making up the model
     * @return a report with one result per validated class (as described above) plus any model-level errors
     * @throws IllegalArgumentException if {@code classes} is null or contains a null element
     */
    ValidationReport validateAll(Collection<Class<?>> classes);
}
