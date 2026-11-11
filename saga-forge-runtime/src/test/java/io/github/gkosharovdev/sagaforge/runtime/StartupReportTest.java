package io.github.gkosharovdev.sagaforge.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gkosharovdev.sagaforge.core.scanner.SagaValidationResult;
import io.github.gkosharovdev.sagaforge.core.scanner.ValidationReport;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link StartupReport} and {@link SagaRuntimeStartupException} (specs/004-saga-engine tasks.md T044,
 * contracts/engine-startup.md).
 */
class StartupReportTest {

    private static final ValidationReport VALID = new ValidationReport(
            List.of(new SagaValidationResult(String.class, List.of())), List.of());
    private static final ValidationReport INVALID = new ValidationReport(
            List.of(new SagaValidationResult(String.class, List.of("class error 1", "class error 2"))),
            List.of("model error"));

    @Test
    @DisplayName("valid iff the validation report is valid and there is no engine error")
    void isValid() {
        assertThat(new StartupReport(VALID, List.of()).isValid()).isTrue();
        assertThat(new StartupReport(VALID, List.of("engine error")).isValid()).isFalse();
        assertThat(new StartupReport(INVALID, List.of()).isValid()).isFalse();
        assertThat(new StartupReport(INVALID, List.of("engine error")).isValid()).isFalse();
    }

    @Test
    @DisplayName("allErrors lists the validation errors, then the engine errors")
    void allErrorsOrder() {
        StartupReport report = new StartupReport(INVALID, List.of("engine error 1", "engine error 2"));

        assertThat(report.allErrors()).containsExactly("class error 1", "class error 2", "model error",
                "engine error 1", "engine error 2");
        assertThat(new StartupReport(VALID, List.of()).allErrors()).isEmpty();
    }

    @Test
    @DisplayName("engineErrors and allErrors are unmodifiable copies")
    void copies() {
        List<String> engineErrors = new ArrayList<>(List.of("engine error"));
        StartupReport report = new StartupReport(VALID, engineErrors);
        engineErrors.add("added later");

        assertThat(report.engineErrors()).containsExactly("engine error");
        assertThatThrownBy(() -> report.engineErrors().add("x")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> report.allErrors().add("x")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("null arguments and null engine errors are rejected")
    void nullsRejected() {
        assertThatThrownBy(() -> new StartupReport(null, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StartupReport(VALID, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StartupReport(VALID, Arrays.asList("ok", null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the exception keeps the report and lists every error, one per line, after a header")
    void exceptionMessage() {
        StartupReport report = new StartupReport(INVALID, List.of("engine error"));

        SagaRuntimeStartupException exception = new SagaRuntimeStartupException(report);

        assertThat(exception.report()).isSameAs(report);
        assertThat(exception.getMessage().lines().toList()).containsExactly(
                "Saga engine refused to start (4 errors):", "class error 1", "class error 2", "model error",
                "engine error");
    }

    @Test
    @DisplayName("the exception rejects a null report")
    void exceptionRejectsNull() {
        assertThatThrownBy(() -> new SagaRuntimeStartupException(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
