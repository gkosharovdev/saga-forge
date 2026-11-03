package io.github.gkosharovdev.sagaforge.core.scanner;

import java.util.List;

/**
 * Immutable result of validating one class: a {@code @Saga} class, or, within
 * {@link AnnotationScanner#validateAll}, a handler, loader, or saver host or an
 * {@code @Aggregate}/{@code @DomainEvent}/{@code @Command} type.
 *
 * @param sagaClass  the validated class (named for its original saga-only use)
 * @param errors     list of validation error messages (empty if valid)
 */
public record SagaValidationResult(
    Class<?> sagaClass,
    List<String> errors
) {
    public SagaValidationResult {
        errors = List.copyOf(errors);
    }

    public boolean isValid() {
        return errors.isEmpty();
    }
}
