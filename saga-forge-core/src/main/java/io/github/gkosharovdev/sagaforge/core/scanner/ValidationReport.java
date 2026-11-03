package io.github.gkosharovdev.sagaforge.core.scanner;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable result of validating a whole saga model with {@link AnnotationScanner#validateAll}.
 *
 * <p>Holds one {@link SagaValidationResult} per validated class plus model-level errors that involve more
 * than one class, such as a command type with several handlers. Passed-in sagas and handler, loader, or
 * saver hosts always have a result; {@code @Aggregate}, {@code @DomainEvent}, and {@code @Command} types
 * that host nothing have one only when they have errors.
 *
 * @param classResults per-class validation results, in validation order
 * @param modelErrors  errors that span several classes (empty if none)
 */
public record ValidationReport(
    List<SagaValidationResult> classResults,
    List<String> modelErrors
) {

    /**
     * Rejects {@code null} lists and {@code null} elements, and stores defensive, unmodifiable copies.
     *
     * @throws IllegalArgumentException if either list, or any element of either list, is {@code null}
     */
    public ValidationReport {
        if (classResults == null) {
            throw new IllegalArgumentException("classResults must not be null");
        }
        if (modelErrors == null) {
            throw new IllegalArgumentException("modelErrors must not be null");
        }
        // List.contains(null) throws NPE on immutable lists, so check elements explicitly.
        if (classResults.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("classResults must not contain null elements");
        }
        if (modelErrors.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("modelErrors must not contain null elements");
        }
        classResults = List.copyOf(classResults);
        modelErrors = List.copyOf(modelErrors);
    }

    /**
     * @return {@code true} only if every class result is valid and there are no model errors
     */
    public boolean isValid() {
        return modelErrors.isEmpty() && classResults.stream().allMatch(SagaValidationResult::isValid);
    }

    /**
     * Every error in the report as one flat list, mainly for diagnostics: the class errors in
     * {@link #classResults()} order, followed by {@link #modelErrors()}. Messages are returned unchanged;
     * they already name the class they concern.
     *
     * @return an unmodifiable list of all error messages (empty if the report is valid)
     */
    public List<String> allErrors() {
        List<String> all = new ArrayList<>();
        for (SagaValidationResult result : classResults) {
            all.addAll(result.errors());
        }
        all.addAll(modelErrors);
        return List.copyOf(all);
    }
}
