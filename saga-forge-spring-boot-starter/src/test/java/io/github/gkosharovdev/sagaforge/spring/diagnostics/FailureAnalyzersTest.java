package io.github.gkosharovdev.sagaforge.spring.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.diagnostics.FailureAnalysis;

import io.github.gkosharovdev.sagaforge.core.scanner.ValidationReport;
import io.github.gkosharovdev.sagaforge.runtime.SagaRuntimeStartupException;
import io.github.gkosharovdev.sagaforge.runtime.StartupReport;
import io.github.gkosharovdev.sagaforge.spring.SagaForgeConfigurationException;

/** Failure analysis of the starter's two startup exceptions (specs/008 FR-017, tasks.md T010). */
class FailureAnalyzersTest {

    @Test
    @DisplayName("a configuration exception is explained with its description and action, also when wrapped")
    void configurationException() {
        SagaForgeConfigurationException cause = new SagaForgeConfigurationException(
                "No storage module is on the class path.", "Add saga-forge-storage-postgres.");

        FailureAnalysis analysis = new SagaForgeConfigurationFailureAnalyzer()
                .analyze(new BeanCreationException("sagaRuntime", "failed", cause));

        assertThat(analysis.getDescription()).contains("No storage module is on the class path.");
        assertThat(analysis.getAction()).isEqualTo("Add saga-forge-storage-postgres.");
    }

    @Test
    @DisplayName("a refused saga model lists every error of the startup report")
    void startupException() {
        StartupReport report = new StartupReport(new ValidationReport(List.of(), List.of("model error R13")),
                List.of("runtime error: no loader"));

        FailureAnalysis analysis = new SagaRuntimeStartupFailureAnalyzer()
                .analyze(new IllegalStateException("context failed", new SagaRuntimeStartupException(report)));

        assertThat(analysis.getDescription()).contains("model error R13").contains("runtime error: no loader");
        assertThat(analysis.getAction()).contains("Fix each error");
    }
}
