package io.github.gkosharovdev.sagaforge.spring.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.scanner.ValidationReport;
import io.github.gkosharovdev.sagaforge.runtime.SagaRuntimeStartupException;
import io.github.gkosharovdev.sagaforge.runtime.StartupReport;
import io.github.gkosharovdev.sagaforge.spring.SagaForgeConfigurationException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.diagnostics.FailureAnalysis;

/** Failure analysis of the starter's two startup exceptions (specs/008 FR-017, tasks.md T010). */
class FailureAnalyzersTest {

  @Test
  @DisplayName(
      "a configuration exception is explained with its description and action, also when wrapped")
  void configurationException() {
    SagaForgeConfigurationException cause =
        new SagaForgeConfigurationException(
            "No storage module is on the class path.", "Add saga-forge-storage-postgres.");

    FailureAnalysis analysis =
        new SagaForgeConfigurationFailureAnalyzer()
            .analyze(new BeanCreationException("sagaRuntime", "failed", cause));

    assertThat(analysis.getDescription()).contains("No storage module is on the class path.");
    assertThat(analysis.getAction()).isEqualTo("Add saga-forge-storage-postgres.");
  }

  @Test
  @DisplayName("a refused saga model lists every error of the startup report")
  void startupException() {
    StartupReport report =
        new StartupReport(
            new ValidationReport(List.of(), List.of("model error R13")),
            List.of("runtime error: no loader"));

    FailureAnalysis analysis =
        new SagaRuntimeStartupFailureAnalyzer()
            .analyze(
                new IllegalStateException(
                    "context failed", new SagaRuntimeStartupException(report)));

    assertThat(analysis.getDescription())
        .contains("model error R13")
        .contains("runtime error: no loader");
    assertThat(analysis.getAction()).contains("Fix each error");
  }

  @Test
  @DisplayName(
      "017: a deadline refused under Spring Modulith interop is told to keep Saga Forge's own log")
  void deadlinesUnderModulithInterop() {
    StartupReport report =
        new StartupReport(
            new ValidationReport(List.of(), List.of()),
            List.of(
                "@Saga class CheckoutSaga declares deadline 'paymentWindow', but the outbox (TransportOutbox) cannot"
                    + " schedule messages. Deadlines need an IN_TRANSACTION outbox."));
    SagaRuntimeStartupException cause = new SagaRuntimeStartupException(report);

    FailureAnalysis analysis =
        new DeadlinesRequireOwnLogFailureAnalyzer(
                new org.springframework.mock.env.MockEnvironment())
            .analyze(new IllegalStateException("failed", cause));

    assertThat(analysis.getDescription())
        .contains("deadline 'paymentWindow'")
        .contains("Spring Modulith interop");
    assertThat(analysis.getAction())
        .isEqualTo(
            "Set saga-forge.transport.modulith-interop=false to keep Saga Forge's own message log.");
    assertThat(
            new DeadlinesRequireOwnLogFailureAnalyzer(
                    new org.springframework.mock.env.MockEnvironment()
                        .withProperty("saga-forge.transport.modulith-interop", "false"))
                .analyze(cause))
        .as("interop off: the general analyzer explains it")
        .isNull();
    assertThat(
            new DeadlinesRequireOwnLogFailureAnalyzer(
                    new org.springframework.mock.env.MockEnvironment())
                .analyze(
                    new SagaRuntimeStartupException(
                        new StartupReport(
                            new ValidationReport(List.of(), List.of("model error R13")),
                            List.of()))))
        .as("another refusal")
        .isNull();
  }
}
