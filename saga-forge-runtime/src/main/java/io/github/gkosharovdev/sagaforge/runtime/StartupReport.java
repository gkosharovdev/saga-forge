package io.github.gkosharovdev.sagaforge.runtime;

import io.github.gkosharovdev.sagaforge.core.scanner.ValidationReport;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Everything {@link SagaRuntime#start()} found wrong with the registered model and plug-ins
 * (contracts/engine-startup.md, FR-034, FR-035).
 *
 * @param validation   the result of {@code ReflectiveAnnotationScanner.validateAll} over the registered classes
 * @param engineErrors the engine's own checks: loader availability, parameter resolvability, saga instantiability
 *                     and {@code EventDispatcher.registerSagaType} failures; unmodifiable
 */
public record StartupReport(ValidationReport validation, List<String> engineErrors) {

    /**
     * @throws IllegalArgumentException if an argument is {@code null} or {@code engineErrors} holds {@code null}
     */
    public StartupReport {
        if (validation == null) {
            throw new IllegalArgumentException("validation must not be null");
        }
        if (engineErrors == null) {
            throw new IllegalArgumentException("engineErrors must not be null");
        }
        if (engineErrors.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("engineErrors must not contain null elements");
        }
        engineErrors = List.copyOf(engineErrors);
    }

    /** {@code true} iff the validation report is valid and there is no engine error. */
    public boolean isValid() {
        return validation.isValid() && engineErrors.isEmpty();
    }

    /** {@code validation.allErrors()}, then {@link #engineErrors()}; unmodifiable. */
    public List<String> allErrors() {
        List<String> all = new ArrayList<>(validation.allErrors());
        all.addAll(engineErrors);
        return List.copyOf(all);
    }
}
