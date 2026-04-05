package io.github.gkosharovdev.sagaforge.core.scanner;

import java.util.List;

/**
 * Immutable result of validating a @Saga-annotated class.
 *
 * @param sagaClass  the validated class
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
