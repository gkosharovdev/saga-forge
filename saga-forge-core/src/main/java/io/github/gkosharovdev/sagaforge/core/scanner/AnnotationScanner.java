package io.github.gkosharovdev.sagaforge.core.scanner;

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
}
