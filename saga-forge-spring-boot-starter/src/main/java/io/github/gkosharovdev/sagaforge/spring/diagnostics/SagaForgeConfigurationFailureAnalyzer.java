package io.github.gkosharovdev.sagaforge.spring.diagnostics;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

import io.github.gkosharovdev.sagaforge.spring.SagaForgeConfigurationException;

/** Explains a {@link SagaForgeConfigurationException} when the application fails to start (FR-017). */
public class SagaForgeConfigurationFailureAnalyzer extends AbstractFailureAnalyzer<SagaForgeConfigurationException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, SagaForgeConfigurationException cause) {
        return new FailureAnalysis("Saga Forge could not be configured: " + cause.description(), cause.action(),
                cause);
    }
}
